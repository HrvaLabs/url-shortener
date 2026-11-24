package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.OwnHostBlocked;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterProvider.CLEANBROWSING;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterProvider.QUAD9;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterStatus.BLOCKED;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterStatus.NO_ANSWER;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterStatus.NOT_BLOCKED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnHostsCheckTest {

    private static final String CUSTOM_HOST = "go.example.com";

    @Mock
    private DnsFilterService dnsFilterService;

    @Mock
    private CustomDomainRepository customDomainRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final InMemoryRedis redis = new InMemoryRedis();
    private final AppProperties appProperties = new AppProperties();
    private OwnHostsCheck ownHostsCheck;

    @BeforeEach
    void setUp() {
        redis.backing(redisTemplate);
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("https://app.hrva.cc/");
        ownHostsCheck = checkWith(redisTemplate);
        when(customDomainRepository.findByStatusIn(Set.of(CustomDomainStatus.ACTIVE))).thenReturn(List.of(CustomDomain.builder().hostname(CUSTOM_HOST).build()));
    }

    @Test
    void checksTheServerTheFrontendAndEveryActiveCustomDomain() {
        when(dnsFilterService.lookup(any())).thenAnswer(invocation -> clean(invocation.getArgument(0)));

        ownHostsCheck.run();

        verify(dnsFilterService, times(1)).lookup("hrva.cc");
        verify(dnsFilterService, times(1)).lookup("app.hrva.cc");
        verify(dnsFilterService, times(1)).lookup(CUSTOM_HOST);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void aBlockedOwnHostPublishesOneAlert() {
        when(dnsFilterService.lookup(any())).thenAnswer(invocation -> clean(invocation.getArgument(0)));
        when(dnsFilterService.lookup("app.hrva.cc")).thenReturn(new DnsFilterReport("app.hrva.cc", true,
                List.of(new DnsFilterAnswer(QUAD9, BLOCKED, null), new DnsFilterAnswer(DnsFilterProvider.CLOUDFLARE, NOT_BLOCKED, null))));

        ownHostsCheck.run();

        verify(eventPublisher).publishEvent(new OwnHostBlocked("app.hrva.cc", List.of("Quad9")));
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void aHostThatDoesNotExistAndALookupThatFailsPublishNothingAndTheRestIsStillChecked() {
        when(dnsFilterService.lookup("hrva.cc")).thenThrow(new IllegalStateException("resolver down"));
        when(dnsFilterService.lookup("app.hrva.cc")).thenReturn(new DnsFilterReport("app.hrva.cc", false, List.of(new DnsFilterAnswer(QUAD9, BLOCKED, null))));
        when(dnsFilterService.lookup(CUSTOM_HOST)).thenReturn(clean(CUSTOM_HOST));

        ownHostsCheck.run();

        verify(dnsFilterService).lookup(CUSTOM_HOST);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void aHostStillBlockedByTheSameFiltersIsAlertedOnceEvenAfterARestart() {
        cleanExceptTheCustomHost(blocked(CUSTOM_HOST, CLEANBROWSING));

        ownHostsCheck.run();
        ownHostsCheck.run();
        checkWith(redisTemplate).run();

        verify(eventPublisher, times(1)).publishEvent(new OwnHostBlocked(CUSTOM_HOST, List.of("CleanBrowsing")));
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void aHostThatMoreFiltersBlockIsAlertedAgain() {
        cleanExceptTheCustomHost(blocked(CUSTOM_HOST, CLEANBROWSING), blocked(CUSTOM_HOST, QUAD9, CLEANBROWSING));

        ownHostsCheck.run();
        ownHostsCheck.run();

        verify(eventPublisher).publishEvent(new OwnHostBlocked(CUSTOM_HOST, List.of("CleanBrowsing")));
        verify(eventPublisher).publishEvent(new OwnHostBlocked(CUSTOM_HOST, List.of("Quad9", "CleanBrowsing")));
    }

    @Test
    void aHostThatWasCleanInBetweenIsAlertedAgainWhenItIsBlocked() {
        cleanExceptTheCustomHost(blocked(CUSTOM_HOST, CLEANBROWSING), clean(CUSTOM_HOST), blocked(CUSTOM_HOST, CLEANBROWSING));

        ownHostsCheck.run();
        ownHostsCheck.run();
        ownHostsCheck.run();

        verify(eventPublisher, times(2)).publishEvent(new OwnHostBlocked(CUSTOM_HOST, List.of("CleanBrowsing")));
    }

    @Test
    void aFailedLookupInBetweenKeepsTheAlertThatWasSent() {
        when(dnsFilterService.lookup(any())).thenAnswer(invocation -> clean(invocation.getArgument(0)));
        when(dnsFilterService.lookup(CUSTOM_HOST)).thenReturn(blocked(CUSTOM_HOST, CLEANBROWSING))
                .thenThrow(new IllegalStateException("resolver down"))
                .thenReturn(blocked(CUSTOM_HOST, CLEANBROWSING));

        ownHostsCheck.run();
        ownHostsCheck.run();
        ownHostsCheck.run();

        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void aBlockedHostIsStillAlertedWhenRedisFails() {
        final var failingRedis = mock(StringRedisTemplate.class);
        when(failingRedis.opsForValue()).thenThrow(new RedisConnectionFailureException("Redis is down"));
        cleanExceptTheCustomHost(blocked(CUSTOM_HOST, CLEANBROWSING));

        checkWith(failingRedis).run();

        verify(eventPublisher).publishEvent(new OwnHostBlocked(CUSTOM_HOST, List.of("CleanBrowsing")));
    }

    @Test
    void aHostThatDoesNotResolveInBetweenKeepsTheAlertThatWasSent() {
        cleanExceptTheCustomHost(blocked(CUSTOM_HOST, CLEANBROWSING), new DnsFilterReport(CUSTOM_HOST, false, List.of()), blocked(CUSTOM_HOST, CLEANBROWSING));

        ownHostsCheck.run();
        ownHostsCheck.run();
        ownHostsCheck.run();

        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void aFilterThatDoesNotAnswerKeepsItsLastKnownBlock() {
        cleanExceptTheCustomHost(blocked(CUSTOM_HOST, QUAD9, CLEANBROWSING),
                answers(CUSTOM_HOST, new DnsFilterAnswer(QUAD9, NO_ANSWER, null), new DnsFilterAnswer(CLEANBROWSING, BLOCKED, null)),
                answers(CUSTOM_HOST, new DnsFilterAnswer(QUAD9, BLOCKED, null), new DnsFilterAnswer(CLEANBROWSING, NO_ANSWER, null)),
                answers(CUSTOM_HOST, new DnsFilterAnswer(QUAD9, NO_ANSWER, null), new DnsFilterAnswer(CLEANBROWSING, NO_ANSWER, null)),
                blocked(CUSTOM_HOST, QUAD9, CLEANBROWSING));

        IntStream.range(0, 5).forEach(run -> ownHostsCheck.run());

        verify(eventPublisher, times(1)).publishEvent(new OwnHostBlocked(CUSTOM_HOST, List.of("Quad9", "CleanBrowsing")));
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void aFilterThatStopsBlockingSendsNothingAndAlertsAgainWhenItStartsAgain() {
        cleanExceptTheCustomHost(blocked(CUSTOM_HOST, QUAD9, CLEANBROWSING), blocked(CUSTOM_HOST, CLEANBROWSING), blocked(CUSTOM_HOST, QUAD9, CLEANBROWSING));

        ownHostsCheck.run();
        ownHostsCheck.run();
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));

        ownHostsCheck.run();
        verify(eventPublisher, times(2)).publishEvent(new OwnHostBlocked(CUSTOM_HOST, List.of("Quad9", "CleanBrowsing")));
    }

    @Test
    void aHostStillBlockedAWeekLaterIsAlertedAgain() {
        cleanExceptTheCustomHost(blocked(CUSTOM_HOST, CLEANBROWSING));

        ownHostsCheck.run();
        ownHostsCheck.run();
        assertThat(redis.timeToLiveOfKeyContaining(CUSTOM_HOST)).isEqualTo(Duration.ofDays(7));
        verify(redisTemplate.opsForValue(), times(1)).set(eq("own-host-blocked:" + CUSTOM_HOST), any(), any(Duration.class));
        redis.expireKeysContaining(CUSTOM_HOST);
        ownHostsCheck.run();

        verify(eventPublisher, times(2)).publishEvent(new OwnHostBlocked(CUSTOM_HOST, List.of("CleanBrowsing")));
    }

    private OwnHostsCheck checkWith(final StringRedisTemplate redis) {
        return new OwnHostsCheck(dnsFilterService, customDomainRepository, redis, appProperties, eventPublisher);
    }

    private void cleanExceptTheCustomHost(final DnsFilterReport first, final DnsFilterReport... next) {
        when(dnsFilterService.lookup(any())).thenAnswer(invocation -> clean(invocation.getArgument(0)));
        when(dnsFilterService.lookup(CUSTOM_HOST)).thenReturn(first, next);
    }

    private static DnsFilterReport clean(final String host) {
        return blocked(host);
    }

    private static DnsFilterReport answers(final String host, final DnsFilterAnswer... answers) {
        return new DnsFilterReport(host, true, List.of(answers));
    }

    private static DnsFilterReport blocked(final String host, final DnsFilterProvider... providers) {
        final var blocking = Set.of(providers);
        return new DnsFilterReport(host, true, Arrays.stream(DnsFilterProvider.values())
                .map(provider -> new DnsFilterAnswer(provider, blocking.contains(provider) ? BLOCKED : NOT_BLOCKED, null))
                .toList());
    }

}
