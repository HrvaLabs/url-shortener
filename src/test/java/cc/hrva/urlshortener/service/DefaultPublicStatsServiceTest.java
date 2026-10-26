package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.stats.PublicStatsResponse;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.BlockedLinks;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.Blocklist;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.DailyClicks;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.service.impl.DefaultPublicStatsService;
import cc.hrva.urlshortener.service.stats.PublicStats;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultPublicStatsServiceTest {

    private static final String KEY = "public:stats";
    private static final Duration HOUR = Duration.ofHours(1);
    private static final LocalDate FROM = LocalDate.of(2026, 9, 26);
    private static final LocalDate TO = LocalDate.of(2026, 10, 25);
    private static final PublicStatsResponse STATS = new PublicStatsResponse(
            FROM,
            TO,
            700,
            40,
            1200,
            List.of(new DailyClicks(FROM, 30), new DailyClicks(TO, 25)),
            new BlockedLinks(12, 3),
            4,
            210L,
            List.of(new Blocklist("HaGeZi TIF medium", BlocklistAction.BLOCK, 120_000), new Blocklist("Abuse feed", BlocklistAction.FLAG, 900)),
            LocalDateTime.of(2026, 10, 26, 10, 0));
    private static final PublicStatsResponse FRESH_SITE = new PublicStatsResponse(FROM, TO, 0, 0, 0, List.of(new DailyClicks(FROM, 0)),
            new BlockedLinks(0, 0), 0, null, List.of(), LocalDateTime.of(2026, 10, 26, 10, 0, 7));

    @Mock
    private PublicStats publicStats;

    private final InMemoryRedis redis = new InMemoryRedis();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private StringRedisTemplate redisTemplate;
    private PublicStatsService publicStatsService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        publicStatsService = new DefaultPublicStatsService(publicStats, redisTemplate, jsonMapper);
    }

    @Test
    void computesOnceAnHour() {
        when(publicStats.collect()).thenReturn(STATS);

        assertThat(publicStatsService.stats()).isEqualTo(STATS);
        assertThat(publicStatsService.stats()).isEqualTo(STATS);

        verify(publicStats, times(1)).collect();
        assertThat(redis.keys()).containsExactly(KEY);
        assertThat(redis.timeToLiveOfKeyContaining(KEY)).isEqualTo(HOUR);
    }

    @Test
    void anExpiredAnswerIsComputedAgain() {
        when(publicStats.collect()).thenReturn(STATS, FRESH_SITE);

        publicStatsService.stats();
        redis.expireKeysContaining(KEY);

        assertThat(publicStatsService.stats()).isEqualTo(FRESH_SITE);
        verify(publicStats, times(2)).collect();
    }

    @Test
    void aCachedAnswerIsServedWithoutComputing() {
        redisTemplate.opsForValue().set(KEY, jsonMapper.writeValueAsString(STATS), HOUR);

        assertThat(publicStatsService.stats()).isEqualTo(STATS);

        verifyNoInteractions(publicStats);
    }

    @Test
    void theCachedJsonReadsBackAsTheSameAnswer() {
        when(publicStats.collect()).thenReturn(STATS, FRESH_SITE);

        for (final var expected : List.of(STATS, FRESH_SITE)) {
            redis.expireKeysContaining(KEY);
            publicStatsService.stats();
            final var cached = redisTemplate.opsForValue().get(KEY);

            assertThat(jsonMapper.readValue(cached, PublicStatsResponse.class)).isEqualTo(expected);
        }
    }

    @Test
    void theCachedJsonHoldsOnlyTheAggregates() {
        when(publicStats.collect()).thenReturn(STATS);

        publicStatsService.stats();
        final var json = jsonMapper.readTree(redisTemplate.opsForValue().get(KEY));

        assertThat(json.propertyNames()).containsExactly("from", "to", "activeLinks", "linksCreated", "clicks", "daily", "blocked",
                "visitorReportsHandled", "averageReviewMinutes", "blocklists", "updatedAt");
        assertThat(json.path("from").asString()).isEqualTo("2026-09-26");
        assertThat(json.path("updatedAt").asString()).isEqualTo("2026-10-26T10:00:00");
        assertThat(json.path("daily").get(0).propertyNames()).containsExactly("date", "clicks");
        assertThat(json.path("blocked").propertyNames()).containsExactly("allTime", "last30Days");
        assertThat(json.path("blocklists").get(0).propertyNames()).containsExactly("name", "action", "entries");
        assertThat(json.path("blocklists").get(0).path("action").asString()).isEqualTo("BLOCK");
    }

    @Test
    void unreadableCachedJsonIsRecomputed(final CapturedOutput output) {
        redisTemplate.opsForValue().set(KEY, "{\"from\": \"not a date\"", HOUR);
        when(publicStats.collect()).thenReturn(STATS);

        assertThat(publicStatsService.stats()).isEqualTo(STATS);

        assertThat(jsonMapper.readValue(redisTemplate.opsForValue().get(KEY), PublicStatsResponse.class)).isEqualTo(STATS);
        assertThat(output).containsOnlyOnce("Public stats cache read failed, computing them exception=tools.jackson.")
                .doesNotContain("not a date");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDownStillAnswers(final CapturedOutput output) {
        final var downTemplate = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(downTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenThrow(new RedisConnectionFailureException("Unable to connect to redis.internal:6379"));
        doThrow(new RedisConnectionFailureException("Unable to connect to redis.internal:6379"))
                .when(valueOperations).set(eq(KEY), anyString(), eq(HOUR));
        when(publicStats.collect()).thenReturn(STATS);

        assertThat(new DefaultPublicStatsService(publicStats, downTemplate, jsonMapper).stats()).isEqualTo(STATS);

        assertThat(output)
                .containsOnlyOnce("Public stats cache read failed, computing them exception=org.springframework.data.redis.RedisConnectionFailureException")
                .containsOnlyOnce("Public stats cache write failed exception=org.springframework.data.redis.RedisConnectionFailureException")
                .doesNotContain("redis.internal");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedWriteStillAnswers() {
        final var template = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("down")).when(valueOperations).set(eq(KEY), anyString(), eq(HOUR));
        when(publicStats.collect()).thenReturn(STATS);

        assertThat(new DefaultPublicStatsService(publicStats, template, jsonMapper).stats()).isEqualTo(STATS);
    }

    @Test
    void noDatabaseTransactionIsHeldWhileRedisIsAsked() throws NoSuchMethodException {
        assertThat(DefaultPublicStatsService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(DefaultPublicStatsService.class.getMethod("stats").isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(PublicStats.class.getMethod("collect").getAnnotation(Transactional.class).readOnly()).isTrue();
    }

}
