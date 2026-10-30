package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.client.CloudflareResult;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudflareTokenCheckTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:30:45.678Z");
    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 10, 29, 12, 30, 45);
    private static final CloudflareResult<Boolean> ACTIVE = new CloudflareResult.Ok<>(true, null);
    private static final CloudflareResult<Boolean> ACCOUNT_TOKEN = new CloudflareResult.Refused<>(401, 1000, "Invalid API Token");

    @Mock
    private CloudflareClient cloudflareClient;

    private final AppProperties appProperties = new AppProperties();
    private CloudflareTokenCheck tokenCheck;

    @BeforeEach
    void setUp() {
        appProperties.getCloudflare().setApiToken("cf-token");
        appProperties.getCloudflare().setZoneId("zone-1");
        tokenCheck = new CloudflareTokenCheck(cloudflareClient, appProperties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void blankTokenOrZoneIsMissing() {
        appProperties.getCloudflare().setApiToken(" ");
        assertThat(tokenCheck.check()).isEqualTo(new CloudflareTokenCheck.TokenState(CloudflareTokenStatus.MISSING, CHECKED_AT,
                "CLOUDFLARE_API_TOKEN or CLOUDFLARE_ZONE_ID isn't set.", null));

        appProperties.getCloudflare().setApiToken("cf-token");
        appProperties.getCloudflare().setZoneId(null);
        assertThat(tokenCheck.check().status()).isEqualTo(CloudflareTokenStatus.MISSING);

        verifyNoInteractions(cloudflareClient);
    }

    @Test
    void verifiedAndProbedIsOk() {
        when(cloudflareClient.verifyToken()).thenReturn(ACTIVE);
        when(cloudflareClient.totalCount()).thenReturn(new CloudflareResult.Ok<>(4, 4));

        final var state = tokenCheck.check();

        assertThat(state).isEqualTo(new CloudflareTokenCheck.TokenState(CloudflareTokenStatus.OK, CHECKED_AT, null, 4));
        assertThat(tokenCheck.current()).isEqualTo(state);
    }

    @Test
    void verifiedButForbiddenIsNoPermission() {
        when(cloudflareClient.verifyToken()).thenReturn(ACTIVE);
        when(cloudflareClient.totalCount()).thenReturn(new CloudflareResult.Refused<>(403, 10000, "Authentication error"));

        assertThat(tokenCheck.check()).isEqualTo(new CloudflareTokenCheck.TokenState(CloudflareTokenStatus.NO_PERMISSION, CHECKED_AT,
                "The token works but can't read custom hostnames on this zone.", null));
    }

    @Test
    void verifiedButAnyOtherRefusalIsNoPermission() {
        when(cloudflareClient.verifyToken()).thenReturn(ACTIVE);
        when(cloudflareClient.totalCount()).thenReturn(new CloudflareResult.Refused<>(400, 7003, "Could not route"));

        assertThat(tokenCheck.check().status()).isEqualTo(CloudflareTokenStatus.NO_PERMISSION);
    }

    @Test
    void accountTokenFailsVerifyButPassesTheProbe() {
        when(cloudflareClient.verifyToken()).thenReturn(ACCOUNT_TOKEN);
        when(cloudflareClient.totalCount()).thenReturn(new CloudflareResult.Ok<>(1, 1));

        assertThat(tokenCheck.check()).isEqualTo(new CloudflareTokenCheck.TokenState(CloudflareTokenStatus.OK, CHECKED_AT, null, 1));
    }

    @Test
    void rejectedEverywhereIsInvalid() {
        when(cloudflareClient.verifyToken()).thenReturn(ACCOUNT_TOKEN);
        when(cloudflareClient.totalCount()).thenReturn(new CloudflareResult.Refused<>(403, 10000, "Authentication error"));

        assertThat(tokenCheck.check()).isEqualTo(new CloudflareTokenCheck.TokenState(CloudflareTokenStatus.INVALID, CHECKED_AT,
                "Cloudflare doesn't accept the token.", null));
    }

    @Test
    void aProbeUnauthorizedIsInvalidEvenWhenVerified() {
        when(cloudflareClient.verifyToken()).thenReturn(ACTIVE);
        when(cloudflareClient.totalCount()).thenReturn(new CloudflareResult.Refused<>(401, 10000, "Authentication error"));

        assertThat(tokenCheck.check().status()).isEqualTo(CloudflareTokenStatus.INVALID);
    }

    @Test
    void aDisabledTokenThatIsForbiddenIsInvalid() {
        when(cloudflareClient.verifyToken()).thenReturn(new CloudflareResult.Ok<>(false, null));
        when(cloudflareClient.totalCount()).thenReturn(new CloudflareResult.Refused<>(403, 10000, "Authentication error"));

        assertThat(tokenCheck.check().status()).isEqualTo(CloudflareTokenStatus.INVALID);
    }

    @Test
    void downIsUnreachable() {
        when(cloudflareClient.verifyToken()).thenReturn(new CloudflareResult.Unreachable<>("timeout"));
        when(cloudflareClient.totalCount()).thenReturn(new CloudflareResult.Unreachable<>("server error"));

        assertThat(tokenCheck.check()).isEqualTo(new CloudflareTokenCheck.TokenState(CloudflareTokenStatus.UNREACHABLE, CHECKED_AT,
                "Cloudflare couldn't be reached. We'll check again.", null));
    }

    @Test
    void messagesAreTheContractTexts() {
        assertThat(CloudflareTokenCheck.messageFor(CloudflareTokenStatus.OK)).isNull();
        assertThat(CloudflareTokenCheck.messageFor(CloudflareTokenStatus.MISSING)).isEqualTo("CLOUDFLARE_API_TOKEN or CLOUDFLARE_ZONE_ID isn't set.");
        assertThat(CloudflareTokenCheck.messageFor(CloudflareTokenStatus.INVALID)).isEqualTo("Cloudflare doesn't accept the token.");
        assertThat(CloudflareTokenCheck.messageFor(CloudflareTokenStatus.NO_PERMISSION))
                .isEqualTo("The token works but can't read custom hostnames on this zone.");
        assertThat(CloudflareTokenCheck.messageFor(CloudflareTokenStatus.UNREACHABLE)).isEqualTo("Cloudflare couldn't be reached. We'll check again.");
    }

    @Test
    void beforeAnyCheckTheStateIsNotCheckedYet() {
        assertThat(tokenCheck.current()).isEqualTo(new CloudflareTokenCheck.TokenState(CloudflareTokenStatus.UNREACHABLE, null,
                "Cloudflare couldn't be reached. We'll check again.", null));

        appProperties.getCloudflare().setZoneId("");
        assertThat(tokenCheck.current()).isEqualTo(new CloudflareTokenCheck.TokenState(CloudflareTokenStatus.MISSING, null,
                "CLOUDFLARE_API_TOKEN or CLOUDFLARE_ZONE_ID isn't set.", null));
        verifyNoInteractions(cloudflareClient);
    }

    @Test
    void theStartupCheckRunsInTheBackgroundWhenTheApplicationIsReady() throws InterruptedException {
        final var settings = new AppProperties();
        try (final var context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfiguration.class);
            context.registerBean(AppProperties.class, () -> settings);
            context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
            context.registerBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));
            context.registerBean(CloudflareClient.class);
            context.registerBean(CloudflareTokenCheck.class);
            context.refresh();
            final var startupCheck = context.getBean(CloudflareTokenCheck.class);

            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));

            final var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (startupCheck.current().checkedAt() == null && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(AopUtils.isAopProxy(startupCheck)).isTrue();
            assertThat(startupCheck.current()).isEqualTo(new CloudflareTokenCheck.TokenState(CloudflareTokenStatus.MISSING, CHECKED_AT,
                    "CLOUDFLARE_API_TOKEN or CLOUDFLARE_ZONE_ID isn't set.", null));
        }
    }

    @Test
    void theStartupCheckNeverThrows() {
        when(cloudflareClient.verifyToken()).thenThrow(new IllegalStateException("boom"));

        tokenCheck.checkAtStartup();

        assertThat(tokenCheck.current().checkedAt()).isNull();
    }

    @EnableAsync
    @Configuration(proxyBeanMethods = false)
    static class AsyncConfiguration {
    }

}
