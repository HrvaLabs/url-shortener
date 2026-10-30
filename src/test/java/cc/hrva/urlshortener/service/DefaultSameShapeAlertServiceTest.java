package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.SameShapeDetected;
import cc.hrva.urlshortener.service.burst.RedisBurstCounter;
import cc.hrva.urlshortener.service.campaign.UrlShapes;
import cc.hrva.urlshortener.service.impl.DefaultSameShapeAlertService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;

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
class DefaultSameShapeAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final Duration DAY = Duration.ofHours(24);
    private static final String SHAPE = "/wqamhcsom?r&t";
    private static final String PATH = "/wqamhcsom";
    private static final String SHAPE_KEY = UrlShapes.of("https://a.com/wqamhcsom?r=1&t=2").orElseThrow().key();
    private static final String COUNTED_KEY = "campaign:shape:" + SHAPE_KEY;
    private static final String ALERTED_KEY = "campaign:alerted:" + SHAPE_KEY;

    @Mock
    private RedisBurstCounter redisBurstCounter;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final InMemoryRedis redis = new InMemoryRedis();
    private final AppProperties appProperties = new AppProperties();
    private final List<String> window = new ArrayList<>();
    private SameShapeAlertService sameShapeAlertService;

    @BeforeEach
    void setUp() {
        final var redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        sameShapeAlertService = new DefaultSameShapeAlertService(redisBurstCounter, urlRepository, eventPublisher, redisTemplate, appProperties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theThirdLinkOnTheThirdDomainAlertsOnce(final CapturedOutput output) {
        counting();
        when(urlRepository.findAllById(any())).thenReturn(List.of(link(42L, "bbb"), link(41L, "aaa"), link(43L, "ccc")));

        sameShapeAlertService.anonymousLinkCreated(41L, "https://ll.omaehhm.com/wqamhcsom/?r=plfN8D&t=1793210826", Outcome.CLEAN);
        sameShapeAlertService.anonymousLinkCreated(42L, "https://qdbplibnsx.fvvxctw.com/wqamhcsom?r=zB3vpw&t=1793185791", Outcome.FLAG);
        verifyNoInteractions(eventPublisher, urlRepository);
        sameShapeAlertService.anonymousLinkCreated(43L, "https://login.c.com/wqamhcsom?t=1&r=2", Outcome.CLEAN);
        sameShapeAlertService.anonymousLinkCreated(44L, "https://d.com/wqamhcsom?r=3&t=4", Outcome.CLEAN);

        verify(redisBurstCounter).recordUnderKey(COUNTED_KEY, "41|omaehhm.com", NOW, DAY);
        verify(redisBurstCounter).recordUnderKey(COUNTED_KEY, "42|fvvxctw.com", NOW, DAY);
        verify(redisBurstCounter).recordUnderKey(COUNTED_KEY, "43|c.com", NOW, DAY);
        verify(eventPublisher, times(1)).publishEvent(any(AdminAlertEvent.class));
        verify(eventPublisher).publishEvent(new SameShapeDetected(SHAPE, PATH, 3, 3, DAY, List.of("ccc", "bbb", "aaa")));
        assertThat(redis.keys()).containsExactly(ALERTED_KEY);
        assertThat(redis.timeToLiveOfKeyContaining(ALERTED_KEY)).isEqualTo(DAY);
        assertThat(output).containsOnlyOnce("Same-shape links shape=" + SHAPE_KEY + " links=3 domains=3")
                .doesNotContain("wqamhcsom", "omaehhm", "c.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theNewestThreeLinksAreTheExamples() {
        window.addAll(List.of("40|a.com", "41|b.com", "42|c.com", "43|d.com"));
        counting();
        when(urlRepository.findAllById(any())).thenReturn(List.of(link(44L, "eee"), link(42L, "ccc"), link(43L, "ddd")));

        sameShapeAlertService.anonymousLinkCreated(44L, "https://e.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        final ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(urlRepository).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(44L, 43L, 42L);
        verify(eventPublisher).publishEvent(new SameShapeDetected(SHAPE, PATH, 5, 5, DAY, List.of("eee", "ddd", "ccc")));
    }

    @Test
    void oneDomainIsNeverACampaign() {
        counting();

        for (var urlId = 41L; urlId <= 45L; urlId++) {
            sameShapeAlertService.anonymousLinkCreated(urlId, "https://www.a.com/wqamhcsom?r=" + urlId + "&t=2", Outcome.CLEAN);
        }

        assertThat(window).hasSize(5).allMatch(member -> member.endsWith("|a.com"));
        verifyNoInteractions(eventPublisher, urlRepository);
        assertThat(redis.isEmpty()).isTrue();
    }

    @Test
    void twoLinksOnTwoDomainsAreNotACampaign() {
        counting();

        sameShapeAlertService.anonymousLinkCreated(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        sameShapeAlertService.anonymousLinkCreated(42L, "https://b.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        verifyNoInteractions(eventPublisher, urlRepository);
        assertThat(redis.isEmpty()).isTrue();
    }

    @Test
    void theSameLinkCountedTwiceIsOneLink() {
        window.addAll(List.of("41|a.com", "42|b.com"));
        counting();

        sameShapeAlertService.anonymousLinkCreated(42L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void indexPhpOnThreeSitesDoesNotAlert() {
        sameShapeAlertService.anonymousLinkCreated(41L, "https://a.com/index.php?id=1", Outcome.CLEAN);
        sameShapeAlertService.anonymousLinkCreated(42L, "https://b.com/index.php?id=2", Outcome.CLEAN);
        sameShapeAlertService.anonymousLinkCreated(43L, "https://c.com/index.php?id=3", Outcome.CLEAN);

        verifyNoInteractions(redisBurstCounter, urlRepository, eventPublisher);
        assertThat(redis.isEmpty()).isTrue();
    }

    @Test
    void anAllowedLinkIsNeverCounted() {
        sameShapeAlertService.anonymousLinkCreated(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.ALLOWED);

        verifyNoInteractions(redisBurstCounter, urlRepository, eventPublisher);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"CLEAN", "FLAG"})
    void cleanAndFlaggedLinksAreCounted(final Outcome outcome) {
        counting();

        sameShapeAlertService.anonymousLinkCreated(41L, "https://a.com/wqamhcsom?r=1&t=2", outcome);

        verify(redisBurstCounter).recordUnderKey(COUNTED_KEY, "41|a.com", NOW, DAY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://203.0.113.7/wqamhcsom?r=1&t=2", "http://[2001:db8::1]/wqamhcsom?r=1&t=2", "http://127.1/wqamhcsom?r=1&t=2"})
    void anIpHostIsNeverCounted(final String longUrl) {
        sameShapeAlertService.anonymousLinkCreated(41L, longUrl, Outcome.CLEAN);

        verifyNoInteractions(redisBurstCounter, urlRepository, eventPublisher);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://", "http://%zz/wqamhcsom?r=1&t=2"})
    void anUnreadableDestinationIsNeverCounted(final String longUrl) {
        sameShapeAlertService.anonymousLinkCreated(41L, longUrl, Outcome.CLEAN);

        verifyNoInteractions(redisBurstCounter, urlRepository, eventPublisher);
    }

    @Test
    void disabledCountsNothing() {
        appProperties.getCampaign().setEnabled(false);

        sameShapeAlertService.anonymousLinkCreated(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        verifyNoInteractions(redisBurstCounter, urlRepository, eventPublisher);
        assertThat(redis.isEmpty()).isTrue();
    }

    @Test
    void theThresholdsComeFromConfiguration() {
        appProperties.getCampaign().setLinks(4);
        appProperties.getCampaign().setDomains(2);
        appProperties.getCampaign().setWindow(Duration.ofHours(6));
        when(redisBurstCounter.recordUnderKey(eq(COUNTED_KEY), anyString(), eq(NOW), eq(Duration.ofHours(6)))).thenAnswer(invocation -> {
            window.add(invocation.getArgument(1));
            return List.copyOf(window);
        });
        when(urlRepository.findAllById(any())).thenReturn(List.of(link(41L, "aaa"), link(42L, "bbb"), link(43L, "ccc"), link(44L, "ddd")));

        sameShapeAlertService.anonymousLinkCreated(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        sameShapeAlertService.anonymousLinkCreated(42L, "https://b.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);
        sameShapeAlertService.anonymousLinkCreated(43L, "https://a.com/wqamhcsom?r=3&t=2", Outcome.CLEAN);
        verifyNoInteractions(eventPublisher);
        sameShapeAlertService.anonymousLinkCreated(44L, "https://b.com/wqamhcsom?r=4&t=2", Outcome.CLEAN);

        verify(eventPublisher).publishEvent(new SameShapeDetected(SHAPE, PATH, 4, 2, Duration.ofHours(6), List.of("ddd", "ccc", "bbb")));
    }

    @Test
    void anExampleLinkDeletedMeanwhileIsLeftOut() {
        window.addAll(List.of("41|a.com", "42|b.com"));
        counting();
        when(urlRepository.findAllById(any())).thenReturn(List.of(link(41L, "aaa"), link(43L, "ccc")));

        sameShapeAlertService.anonymousLinkCreated(43L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN);

        verify(eventPublisher).publishEvent(new SameShapeDetected(SHAPE, PATH, 3, 3, DAY, List.of("ccc", "aaa")));
    }

    @Test
    void redisDownIsSkippedWithOneWarnLine(final CapturedOutput output) {
        when(redisBurstCounter.recordUnderKey(any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to redis.internal:6379"));

        assertThatCode(() -> sameShapeAlertService.anonymousLinkCreated(41L, "https://a.com/wqamhcsom?r=1&t=2", Outcome.CLEAN))
                .doesNotThrowAnyException();

        verifyNoInteractions(urlRepository, eventPublisher);
        assertThat(output).containsOnlyOnce("Same-shape check skipped shape=" + SHAPE_KEY
                        + " exception=org.springframework.data.redis.RedisConnectionFailureException")
                .doesNotContain("redis.internal", "wqamhcsom", "a.com");
    }

    @Test
    void redisFailingAfterTheCountMeansNoAlert(final CapturedOutput output) {
        final var template = mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenThrow(new RedisConnectionFailureException("Unable to connect"));
        window.addAll(List.of("41|a.com", "42|b.com"));
        counting();
        final var service = new DefaultSameShapeAlertService(redisBurstCounter, urlRepository, eventPublisher, template, appProperties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatCode(() -> service.anonymousLinkCreated(43L, "https://c.com/wqamhcsom?r=1&t=2", Outcome.CLEAN)).doesNotThrowAnyException();

        verifyNoInteractions(urlRepository, eventPublisher);
        assertThat(output).containsOnlyOnce("Same-shape check skipped shape=" + SHAPE_KEY);
    }

    @Test
    void theCheckRunsOnTheDefaultTaskExecutor() throws NoSuchMethodException {
        final var async = DefaultSameShapeAlertService.class.getMethod("anonymousLinkCreated", long.class, String.class, Outcome.class)
                .getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEmpty();
    }

    private void counting() {
        when(redisBurstCounter.recordUnderKey(eq(COUNTED_KEY), anyString(), eq(NOW), eq(DAY))).thenAnswer(invocation -> {
            window.add(invocation.getArgument(1));
            return List.copyOf(window);
        });
    }

    private static Url link(final long id, final String alias) {
        return Url.builder().id(id).shortUrl(alias).build();
    }

}
