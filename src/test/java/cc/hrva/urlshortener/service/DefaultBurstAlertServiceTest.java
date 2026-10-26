package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BurstDetected;
import cc.hrva.urlshortener.service.burst.CreatedLink;
import cc.hrva.urlshortener.service.burst.RedisBurstCounter;
import cc.hrva.urlshortener.service.impl.DefaultBurstAlertService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultBurstAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final String DOMAIN = "evil-login.com";
    private static final String LONG_URL = "https://evil-login.com/verify?account=1";
    private static final String ALERTED_KEY = "burst:alerted:evil-login.com";
    private static final String ANONYMOUS = "anonymous:" + "a".repeat(64);

    @Mock
    private RedisBurstCounter redisBurstCounter;

    @Mock
    private DomainAgeService domainAgeService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final InMemoryRedis redis = new InMemoryRedis();
    private final AppProperties appProperties = new AppProperties();
    private StringRedisTemplate redisTemplate;
    private BurstAlertService burstAlertService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        burstAlertService = service(redisTemplate);
    }

    @Test
    void fiveLinksFromTwoCreatorsToANewDomainAlertOnce(final CapturedOutput output) {
        counted(members(5, "user:7", "user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenReturn(Optional.of(TODAY.minusDays(4)));

        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

        verify(redisBurstCounter).record(DOMAIN, "41|user:7", NOW, HOUR);
        verify(eventPublisher, times(1)).publishEvent(new BurstDetected(DOMAIN, 5, 2, 4, HOUR));
        assertThat(redis.keys()).containsExactly(ALERTED_KEY);
        assertThat(redis.timeToLiveOfKeyContaining(ALERTED_KEY)).isEqualTo(Duration.ofHours(24));
        assertThat(output).containsOnlyOnce("Burst of new links domain=evil-login.com links=5 creators=2 ageDays=4")
                .doesNotContain("verify", "user:7", "user:8");
    }

    @Test
    void oneOwnerBatchShorteningIsNotABurst() {
        counted(members(10, "user:7"));

        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

        verifyNoInteractions(domainAgeService, eventPublisher);
        assertThat(redis.isEmpty()).isTrue();
    }

    @Test
    void oneAnonymousNetworkIsOneCreator() {
        counted(members(10, ANONYMOUS));

        burstAlertService.linkCreated(link(41L, LONG_URL, ANONYMOUS, Outcome.CLEAN));

        verifyNoInteractions(domainAgeService, eventPublisher);
    }

    @Test
    void anAccountAndAnAnonymousNetworkAreTwoCreators() {
        counted(members(5, "user:7", ANONYMOUS));
        when(domainAgeService.registeredOn(DOMAIN)).thenReturn(Optional.empty());

        burstAlertService.linkCreated(link(41L, LONG_URL, ANONYMOUS, Outcome.CLEAN));

        verify(eventPublisher).publishEvent(new BurstDetected(DOMAIN, 5, 2, null, HOUR));
    }

    @Test
    void oneReusedLinkFromFiveNetworksIsNotABurst() {
        counted(IntStream.rangeClosed(1, 5).mapToObj(network -> "41|anonymous:" + Integer.toString(network).repeat(64)).toList());

        burstAlertService.linkCreated(link(41L, LONG_URL, "anonymous:" + "5".repeat(64), Outcome.CLEAN));

        verifyNoInteractions(domainAgeService, eventPublisher);
        assertThat(redis.isEmpty()).isTrue();
    }

    @Test
    void reusedLinksCountOnceEachAndARealWaveStillAlerts() {
        counted(List.of("41|" + ANONYMOUS, "41|user:7", "42|user:7", "43|user:8", "44|user:8", "45|user:7", "45|user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenReturn(Optional.empty());

        burstAlertService.linkCreated(link(45L, LONG_URL, "user:8", Outcome.CLEAN));

        verify(eventPublisher).publishEvent(new BurstDetected(DOMAIN, 5, 3, null, HOUR));
    }

    @Test
    void fourDistinctLinksAreNotABurstHoweverOftenTheyAreReused() {
        counted(List.of("41|user:7", "41|user:8", "42|user:7", "42|user:8", "43|user:7", "44|user:8"));

        burstAlertService.linkCreated(link(44L, LONG_URL, "user:8", Outcome.CLEAN));

        verifyNoInteractions(domainAgeService, eventPublisher);
    }

    @Test
    void fourLinksAreNotABurst() {
        counted(members(4, "user:7", "user:8"));

        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

        verifyNoInteractions(domainAgeService, eventPublisher);
    }

    @Test
    void anOldDomainIsNotABurst() {
        counted(members(5, "user:7", "user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenReturn(Optional.of(LocalDate.of(2024, 10, 1)));

        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

        verifyNoInteractions(eventPublisher);
        assertThat(redis.isEmpty()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"0, true", "1, true", "29, true", "30, false", "31, false"})
    void aDomainIsNewUntilItIsThirtyDaysOld(final int ageDays, final boolean alerts) {
        counted(members(5, "user:7", "user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenReturn(Optional.of(TODAY.minusDays(ageDays)));

        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

        verify(eventPublisher, times(alerts ? 1 : 0)).publishEvent(new BurstDetected(DOMAIN, 5, 2, ageDays, HOUR));
        verify(eventPublisher, times(alerts ? 1 : 0)).publishEvent(any(AdminAlertEvent.class));
    }

    @Test
    void aRegistrationDateAfterTodayCountsAsRegisteredToday() {
        counted(members(5, "user:7", "user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenReturn(Optional.of(TODAY.plusDays(1)));

        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

        verify(eventPublisher).publishEvent(new BurstDetected(DOMAIN, 5, 2, 0, HOUR));
    }

    @Test
    void anUnknownDomainAgeCountsAsNew() {
        counted(members(5, "user:7", "user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenReturn(Optional.empty());

        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

        verify(eventPublisher).publishEvent(new BurstDetected(DOMAIN, 5, 2, null, HOUR));
    }

    @Test
    void aDomainAlertsOnlyOncePerDay() {
        redisTemplate.opsForValue().set(ALERTED_KEY, "1", Duration.ofHours(24));
        counted(members(6, "user:7", "user:8"));

        burstAlertService.linkCreated(link(42L, LONG_URL, "user:8", Outcome.CLEAN));

        verifyNoInteractions(domainAgeService, eventPublisher);
    }

    @Test
    void aSecondWaveWithinADayIsSilentAndTheNextDayAlertsAgain() {
        counted(members(5, "user:7", "user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenReturn(Optional.empty());

        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));
        burstAlertService.linkCreated(link(42L, LONG_URL, "user:8", Outcome.CLEAN));
        verify(eventPublisher, times(1)).publishEvent(any(AdminAlertEvent.class));

        redis.expireKeysContaining(ALERTED_KEY);
        burstAlertService.linkCreated(link(43L, LONG_URL, "user:7", Outcome.CLEAN));

        verify(eventPublisher, times(2)).publishEvent(new BurstDetected(DOMAIN, 5, 2, null, HOUR));
    }

    @Test
    void aCheckThatLosesTheRaceForTheAlertedKeyPublishesNothing() {
        counted(members(5, "user:7", "user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenAnswer(invocation -> {
            redisTemplate.opsForValue().set(ALERTED_KEY, "1", Duration.ofHours(24));
            return Optional.empty();
        });

        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @SuppressWarnings("unchecked")
    void noAnswerToTheAlertedKeyClaimPublishesNothing() {
        final var template = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(ALERTED_KEY, "1", Duration.ofHours(24))).thenReturn(null);
        counted(members(5, "user:7", "user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenReturn(Optional.empty());

        service(template).linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aTrustedDomainIsNeverCounted() {
        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.ALLOWED));

        verifyNoInteractions(redisBurstCounter, domainAgeService, eventPublisher);
        assertThat(redis.isEmpty()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"CLEAN", "FLAG"})
    void cleanAndFlaggedLinksAreCounted(final Outcome outcome) {
        counted(members(1, "user:7"));

        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", outcome));

        verify(redisBurstCounter).record(DOMAIN, "41|user:7", NOW, HOUR);
    }

    @Test
    void subdomainsCountTowardTheirRegistrableDomain() {
        counted(members(5, "user:7", "user:8"));
        when(domainAgeService.registeredOn("a.evil-login.com")).thenReturn(Optional.empty());

        burstAlertService.linkCreated(link(41L, "https://A.Evil-Login.com/x", "user:7", Outcome.CLEAN));

        verify(redisBurstCounter).record(DOMAIN, "41|user:7", NOW, HOUR);
        verify(eventPublisher).publishEvent(new BurstDetected(DOMAIN, 5, 2, null, HOUR));
        assertThat(redis.keys()).containsExactly(ALERTED_KEY);
    }

    @Test
    void sharedHostingSitesCountSeparately() {
        when(redisBurstCounter.record(eq("x.github.io"), anyString(), eq(NOW), eq(HOUR))).thenReturn(members(1, "user:7"));

        burstAlertService.linkCreated(link(41L, "https://x.github.io/login", "user:7", Outcome.CLEAN));

        verify(redisBurstCounter).record("x.github.io", "41|user:7", NOW, HOUR);
    }

    @Test
    void anInternationalHostCountsAsItsAsciiForm() {
        when(redisBurstCounter.record(eq("xn--okolada-i6a.hr"), anyString(), eq(NOW), eq(HOUR))).thenReturn(members(1, "user:7"));

        burstAlertService.linkCreated(link(41L, "https://prijava.Čokolada.hr/", "user:7", Outcome.CLEAN));

        verify(redisBurstCounter).record("xn--okolada-i6a.hr", "41|user:7", NOW, HOUR);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"http://203.0.113.7/login", "http://[2001:db8::1]/login", "http://127.1/x", "https://", "http://%zz/"})
    void ipAndUnreadableDestinationsAreSkipped(final String longUrl) {
        burstAlertService.linkCreated(link(41L, longUrl, "user:7", Outcome.CLEAN));

        verifyNoInteractions(redisBurstCounter, domainAgeService, eventPublisher);
    }

    @Test
    void redisDownMeansNoAlertAndNoException(final CapturedOutput output) {
        when(redisBurstCounter.record(any(), any(), any(), any())).thenThrow(new RedisConnectionFailureException("Unable to connect to redis.internal:6379"));

        assertThatCode(() -> burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN))).doesNotThrowAnyException();

        verifyNoInteractions(domainAgeService, eventPublisher);
        assertThat(output).containsOnlyOnce("Burst check skipped domain=evil-login.com exception=org.springframework.data.redis.RedisConnectionFailureException")
                .doesNotContain("redis.internal");
    }

    @Test
    void redisFailingAfterTheCountMeansNoAlert(final CapturedOutput output) {
        counted(members(5, "user:7", "user:8"));
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Unable to connect"));

        assertThatCode(() -> burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN))).doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
        assertThat(output).containsOnlyOnce("Burst check skipped domain=evil-login.com");
    }

    @Test
    void aFailedDomainAgeLookupMeansNoAlert(final CapturedOutput output) {
        counted(members(5, "user:7", "user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenThrow(new IllegalStateException("lookup failed"));

        assertThatCode(() -> burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN))).doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
        assertThat(redis.isEmpty()).isTrue();
        assertThat(output).containsOnlyOnce("Burst check skipped domain=evil-login.com exception=java.lang.IllegalStateException");
    }

    @Test
    void theThresholdAndWindowComeFromTheProperties() {
        appProperties.getBurst().setLinks(3);
        appProperties.getBurst().setWindow(Duration.ofMinutes(30));
        when(redisBurstCounter.record(DOMAIN, "41|user:7", NOW, Duration.ofMinutes(30))).thenReturn(members(3, "user:7", "user:8"));
        when(redisBurstCounter.record(DOMAIN, "42|user:7", NOW, Duration.ofMinutes(30))).thenReturn(members(2, "user:7", "user:8"));
        when(domainAgeService.registeredOn(DOMAIN)).thenReturn(Optional.empty());

        burstAlertService.linkCreated(link(42L, LONG_URL, "user:7", Outcome.CLEAN));
        verifyNoInteractions(eventPublisher);
        burstAlertService.linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

        verify(eventPublisher).publishEvent(new BurstDetected(DOMAIN, 3, 2, null, Duration.ofMinutes(30)));
    }

    @Test
    void theCheckRunsOnTheDefaultTaskExecutor() throws NoSuchMethodException {
        final var async = DefaultBurstAlertService.class.getMethod("linkCreated", CreatedLink.class).getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEmpty();
    }

    @Test
    void theCheckRunsInTheBackgroundSoTheCreateNeverWaitsForIt() throws InterruptedException {
        final var counted = new CountDownLatch(1);
        final var countingThread = new AtomicReference<Thread>();
        when(redisBurstCounter.record(any(), any(), any(), any())).thenAnswer(invocation -> {
            countingThread.set(Thread.currentThread());
            counted.countDown();
            return List.of();
        });
        final var executor = Executors.newSingleThreadExecutor();
        try {
            final var proxyFactory = new ProxyFactory(burstAlertService);
            proxyFactory.addAdvisor(new AsyncAnnotationAdvisor(() -> executor, SimpleAsyncUncaughtExceptionHandler::new));

            ((BurstAlertService) proxyFactory.getProxy()).linkCreated(link(41L, LONG_URL, "user:7", Outcome.CLEAN));

            assertThat(counted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(countingThread.get()).isNotNull().isNotSameAs(Thread.currentThread());
        } finally {
            executor.shutdownNow();
        }
    }

    private void counted(final List<String> members) {
        when(redisBurstCounter.record(eq(DOMAIN), anyString(), eq(NOW), eq(HOUR))).thenReturn(members);
    }

    private static List<String> members(final int links, final String... creators) {
        return IntStream.range(0, links).mapToObj(index -> (40 + index) + "|" + creators[index % creators.length]).toList();
    }

    private static CreatedLink link(final long urlId, final String longUrl, final String creator, final Outcome outcome) {
        return new CreatedLink(urlId, longUrl, creator, outcome);
    }

    private BurstAlertService service(final StringRedisTemplate template) {
        return new DefaultBurstAlertService(redisBurstCounter, domainAgeService, eventPublisher, template, appProperties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

}
