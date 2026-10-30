package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.SiteverifyResult;
import cc.hrva.urlshortener.client.TurnstileClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.HumanCheckUnavailableException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.impl.DefaultHumanCheckService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultHumanCheckServiceTest {

    private static final String SITE_KEY = "1x00000000000000000000AA";
    private static final String SECRET_KEY = "1x0000000000000000000000000000000AA";
    private static final String TOKEN = "XXXX.DUMMY.TOKEN.XXXX";
    private static final String REFUSAL = "Complete the check and try again.";
    private static final String UNAVAILABLE = "We couldn't run the check. Try again in a minute.";

    private final AppProperties appProperties = new AppProperties();
    private final SettingsService settingsService = SettingsServiceFixtures.defaults(appProperties);

    @Mock
    private TurnstileClient turnstileClient;

    private HumanCheckService humanCheckService;

    @BeforeEach
    void setUp() {
        appProperties.setFrontendUrl("https://app.hrva.cc");
        appProperties.getTurnstile().setSiteKey(SITE_KEY);
        appProperties.getTurnstile().setSecretKey(SECRET_KEY);
        humanCheckService = new DefaultHumanCheckService(settingsService, turnstileClient, appProperties);
    }

    @Test
    void noKeysMeansNoCheck() {
        appProperties.getTurnstile().setSecretKey(" ");

        assertThatCode(() -> humanCheckService.verify(null)).doesNotThrowAnyException();

        appProperties.getTurnstile().setSecretKey(SECRET_KEY);
        appProperties.getTurnstile().setSiteKey(null);

        assertThatCode(() -> humanCheckService.verify(null)).doesNotThrowAnyException();
        verifyNoInteractions(turnstileClient);
    }

    @Test
    void switchOffMeansNoCheck() {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.HUMAN_CHECK);

        assertThatCode(() -> humanCheckService.verify(null)).doesNotThrowAnyException();
        assertThatCode(() -> humanCheckService.verify(TOKEN)).doesNotThrowAnyException();
        verifyNoInteractions(turnstileClient);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aMissingTokenIsRefused(final String token) {
        assertThatThrownBy(() -> humanCheckService.verify(token))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(REFUSAL);
        verifyNoInteractions(turnstileClient);
    }

    @Test
    void aTokenLongerThanCloudflareAllowsIsRefusedWithoutAsking() {
        assertThatThrownBy(() -> humanCheckService.verify("a".repeat(2049)))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(REFUSAL);
        verifyNoInteractions(turnstileClient);
    }

    @Test
    void aValidTokenForTheFrontendHostPasses() {
        when(turnstileClient.verify(TOKEN)).thenReturn(new SiteverifyResult.Answered(true, "app.hrva.cc", List.of()));

        assertThatCode(() -> humanCheckService.verify(TOKEN)).doesNotThrowAnyException();
        verify(turnstileClient).verify(TOKEN);
    }

    @Test
    void theHostnameIsComparedWithoutCase() {
        appProperties.setFrontendUrl("https://App.Hrva.cc/");
        when(turnstileClient.verify(TOKEN)).thenReturn(new SiteverifyResult.Answered(true, "app.hrva.cc", List.of()));

        assertThatCode(() -> humanCheckService.verify(TOKEN)).doesNotThrowAnyException();
    }

    @Test
    void anotherHostnameIsRefused(final CapturedOutput output) {
        when(turnstileClient.verify(TOKEN))
                .thenReturn(new SiteverifyResult.Answered(true, "evil.example", List.of()))
                .thenReturn(new SiteverifyResult.Answered(true, null, List.of()))
                .thenReturn(new SiteverifyResult.Answered(true, "app.hrva.cc.evil.example", List.of()));

        for (var attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> humanCheckService.verify(TOKEN))
                    .isInstanceOf(UrlValidationException.class)
                    .hasMessage(REFUSAL);
        }
        assertThat(output).contains("hostname=evil.example").doesNotContain(TOKEN);
    }

    @Test
    void aUsedTokenIsRefused(final CapturedOutput output) {
        when(turnstileClient.verify(TOKEN)).thenReturn(new SiteverifyResult.Answered(false, null, List.of("timeout-or-duplicate")));

        assertThatThrownBy(() -> humanCheckService.verify(TOKEN))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(REFUSAL);
        assertThat(output).contains("timeout-or-duplicate").doesNotContain(TOKEN);
    }

    @Test
    void anInvalidTokenIsRefused() {
        when(turnstileClient.verify(TOKEN)).thenReturn(new SiteverifyResult.Answered(false, null, List.of("invalid-input-response")));

        assertThatThrownBy(() -> humanCheckService.verify(TOKEN))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(REFUSAL);
    }

    @Test
    void anUnreachableSiteverifyFailsClosed(final CapturedOutput output) {
        when(turnstileClient.verify(any())).thenReturn(new SiteverifyResult.Unreachable("timeout"));

        assertThatThrownBy(() -> humanCheckService.verify(TOKEN))
                .isInstanceOf(HumanCheckUnavailableException.class)
                .hasMessage(UNAVAILABLE);
        assertThat(output).contains("reason=timeout").doesNotContain(TOKEN).doesNotContain(SECRET_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-input-secret", "missing-input-secret", "internal-error"})
    void aFailureOnCloudflaresSideOrOursFailsClosed(final String errorCode, final CapturedOutput output) {
        when(turnstileClient.verify(TOKEN)).thenReturn(new SiteverifyResult.Answered(false, null, List.of(errorCode)));

        assertThatThrownBy(() -> humanCheckService.verify(TOKEN))
                .isInstanceOf(HumanCheckUnavailableException.class)
                .hasMessage(UNAVAILABLE);
        assertThat(output).contains(errorCode).doesNotContain(SECRET_KEY);
    }

}
