package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.repository.stats.AnonymousTrafficRepository;
import cc.hrva.urlshortener.repository.stats.AnonymousTrafficRepository.BusyLink;
import cc.hrva.urlshortener.repository.stats.AnonymousTrafficRepository.DayClicks;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.AnonymousLinkBusy;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.AnonymousTrafficShare;
import cc.hrva.urlshortener.service.alert.AlertedLink;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.impl.DefaultTrafficAlertService;
import cc.hrva.urlshortener.service.notification.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultTrafficAlertServiceTest {

    private static final Instant LATE_EVENING = Instant.parse("2026-10-29T22:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 30);
    private static final Duration TWO_DAYS = Duration.ofHours(48);
    private static final BusyLink BUSY = new BusyLink(42L, "abc12", "https://example-login.top/verify?id=7", 25);
    private static final AlertedLink ALERTED = new AlertedLink(42L, "abc12", "example-login.top", null, null);

    @Mock
    private AnonymousTrafficRepository anonymousTrafficRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final InMemoryRedis redis = new InMemoryRedis();
    private final AppProperties appProperties = new AppProperties();
    private final MutableClock clock = new MutableClock(LATE_EVENING);
    private StringRedisTemplate redisTemplate;
    private TrafficAlertService trafficAlertService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        trafficAlertService = service(redisTemplate);
    }

    @Test
    void aLinkReaching25ClicksAlertsOncePerDay() {
        when(anonymousTrafficRepository.busyAnonymousLinks(eq(TODAY), eq(25L), anyInt())).thenReturn(List.of(BUSY));
        when(anonymousTrafficRepository.topCountry(42L, TODAY)).thenReturn(Optional.of("PT"));
        quietDay(TODAY);

        trafficAlertService.check();
        trafficAlertService.check();

        verify(eventPublisher, times(1)).publishEvent(any(AdminAlertEvent.class));
        verify(eventPublisher).publishEvent(new AnonymousLinkBusy(ALERTED, 25, "PT"));
        assertThat(redis.keys()).containsExactly("traffic:link:2026-10-29:42");
        assertThat(redis.timeToLiveOfKeyContaining("traffic:link:2026-10-29:42")).isEqualTo(TWO_DAYS);

        clock.set(Instant.parse("2026-10-29T23:30:00Z"));
        when(anonymousTrafficRepository.busyAnonymousLinks(eq(TOMORROW), eq(25L), anyInt())).thenReturn(List.of(withClicks(BUSY, 26)));
        when(anonymousTrafficRepository.topCountry(42L, TOMORROW)).thenReturn(Optional.empty());
        quietDay(TOMORROW);

        trafficAlertService.check();

        verify(eventPublisher, times(2)).publishEvent(any(AdminAlertEvent.class));
        verify(eventPublisher).publishEvent(new AnonymousLinkBusy(ALERTED, 26, null));
        assertThat(redis.keys()).containsExactlyInAnyOrder("traffic:link:2026-10-29:42", "traffic:link:2026-10-30:42");
    }

    @Test
    void aSignedInLinkNeverAlerts() {
        when(anonymousTrafficRepository.dayClicks(TODAY)).thenReturn(new DayClicks(5_000, 0));

        trafficAlertService.check();

        verify(anonymousTrafficRepository).busyAnonymousLinks(eq(TODAY), eq(25L), anyInt());
        verify(anonymousTrafficRepository, never()).topCountry(any(Long.class), any());
        verifyNoInteractions(eventPublisher);
        assertThat(redis.isEmpty()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"100, 199, true", "100, 200, false", "99, 100, false"})
    void theShareAlertNeedsMoreThanHalfAndAtLeast100(final long anonymous, final long total, final boolean alerts) {
        when(anonymousTrafficRepository.dayClicks(TODAY)).thenReturn(new DayClicks(total, anonymous));

        trafficAlertService.check();

        if (alerts) {
            verify(eventPublisher).publishEvent(new AnonymousTrafficShare(anonymous, total));
            assertThat(redis.keys()).containsExactly("traffic:share:2026-10-29");
            assertThat(redis.timeToLiveOfKeyContaining("traffic:share:2026-10-29")).isEqualTo(TWO_DAYS);
        } else {
            verifyNoInteractions(eventPublisher);
            assertThat(redis.isEmpty()).isTrue();
        }
    }

    @Test
    void theShareAlertFiresOncePerDay() {
        when(anonymousTrafficRepository.dayClicks(TODAY)).thenReturn(new DayClicks(150, 120), new DayClicks(190, 160));

        trafficAlertService.check();
        trafficAlertService.check();

        verify(eventPublisher, times(1)).publishEvent(any(AdminAlertEvent.class));
        verify(eventPublisher).publishEvent(new AnonymousTrafficShare(120, 150));

        clock.advance(Duration.ofHours(2));
        when(anonymousTrafficRepository.dayClicks(TOMORROW)).thenReturn(new DayClicks(150, 120));

        trafficAlertService.check();

        verify(eventPublisher, times(2)).publishEvent(any(AdminAlertEvent.class));
        assertThat(redis.keys()).containsExactlyInAnyOrder("traffic:share:2026-10-29", "traffic:share:2026-10-30");
    }

    @Test
    void disabledSendsNothing() {
        appProperties.getTrafficAlerts().setEnabled(false);

        trafficAlertService.check();

        verifyNoInteractions(anonymousTrafficRepository, eventPublisher);
        assertThat(redis.isEmpty()).isTrue();
    }

    @Test
    void redisDownSkipsTheRun(final CapturedOutput output) {
        final var downTemplate = mock(StringRedisTemplate.class);
        when(downTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Unable to connect to redis.internal:6379"));
        when(anonymousTrafficRepository.busyAnonymousLinks(eq(TODAY), eq(25L), anyInt())).thenReturn(List.of(BUSY));
        lenient().when(anonymousTrafficRepository.dayClicks(TODAY)).thenReturn(new DayClicks(150, 120));

        assertThatCode(() -> service(downTemplate).check()).doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
        verify(anonymousTrafficRepository, never()).topCountry(any(Long.class), any());
        assertThat(output).containsOnlyOnce("Traffic check skipped day=2026-10-29 exception=org.springframework.data.redis.RedisConnectionFailureException")
                .doesNotContain("redis.internal", "abc12", "example-login");
    }

    @Test
    void thresholdsComeFromConfiguration() {
        appProperties.getTrafficAlerts().setLinkClicks(40);
        appProperties.getTrafficAlerts().setSharePercent(70);
        appProperties.getTrafficAlerts().setMinAnonymousClicks(10);
        when(anonymousTrafficRepository.busyAnonymousLinks(eq(TODAY), eq(40L), anyInt())).thenReturn(List.of(withClicks(BUSY, 40)));
        when(anonymousTrafficRepository.topCountry(42L, TODAY)).thenReturn(Optional.of("HR"));
        when(anonymousTrafficRepository.dayClicks(TODAY)).thenReturn(new DayClicks(20, 15));

        trafficAlertService.check();

        verify(eventPublisher).publishEvent(new AnonymousLinkBusy(ALERTED, 40, "HR"));
        verify(eventPublisher).publishEvent(new AnonymousTrafficShare(15, 20));
    }

    @Test
    void theCheckRunsOutsideATransaction() throws NoSuchMethodException {
        final var transactional = DefaultTrafficAlertService.class.getMethod("check").getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    private TrafficAlertService service(final StringRedisTemplate template) {
        return new DefaultTrafficAlertService(anonymousTrafficRepository, new AnalyticsDays(clock, appProperties), eventPublisher, template,
                appProperties);
    }

    private void quietDay(final LocalDate day) {
        when(anonymousTrafficRepository.dayClicks(day)).thenReturn(new DayClicks(0, 0));
    }

    private static BusyLink withClicks(final BusyLink link, final long clicks) {
        return new BusyLink(link.urlId(), link.shortUrl(), link.longUrl(), clicks);
    }

}
