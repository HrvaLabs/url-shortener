package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.stats.CacheStats;
import cc.hrva.urlshortener.dto.stats.DailyCounts;
import cc.hrva.urlshortener.dto.stats.RedirectTiming;
import cc.hrva.urlshortener.dto.stats.RedisKeyCounts;
import cc.hrva.urlshortener.dto.stats.RequestCounts;
import cc.hrva.urlshortener.dto.stats.SystemStatsResponse.ComponentHealth;
import cc.hrva.urlshortener.dto.stats.SystemStatsResponse.DatabaseStats;
import cc.hrva.urlshortener.dto.stats.SystemStatsResponse.RedisStats;
import cc.hrva.urlshortener.repository.ShortUrlUniqueIndex;
import cc.hrva.urlshortener.repository.stats.HourlyCount;
import cc.hrva.urlshortener.repository.stats.HourlyCountRepository;
import cc.hrva.urlshortener.repository.stats.SystemStatsRepository;
import cc.hrva.urlshortener.repository.stats.SystemStatsRepository.DatabaseInfo;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore.RedisMemory;
import cc.hrva.urlshortener.service.stats.RuntimeMetrics.JvmHeap;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.RedisConnectionFailureException;

import static cc.hrva.urlshortener.repository.stats.HourlySeries.EMAILS_BY_STATUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class SystemStatsTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-10-26T06:48:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 10, 20);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);
    private static final StatsWindow WINDOW =
            new StatsWindow(7, FROM, TO, LocalDateTime.of(2026, 10, 19, 22, 0), LocalDateTime.of(2026, 10, 26, 23, 0));
    private static final List<String> STATUSES = List.of("SENT", "FAILED", "SENDING", "RESENT");
    private static final List<String> PREFIXES =
            List.of("blocklist:", "rate-limit:", "analytics:", "rdap:", "dbl:", "mcp:connect:", "urls::");
    private static final List<RedirectTiming> NO_REDIRECTS =
            List.of(new RedirectTiming("cache", 0, null, null), new RedirectTiming("db", 0, null, null));
    private static final DatabaseInfo POSTGRES = new DatabaseInfo("PostgreSQL", "18.0", 12_345_678L);
    private static final String REDIS_ADDRESS = "redis.internal.example:6379";

    @Mock
    private RuntimeMetrics runtimeMetrics;

    @Mock
    private HealthStatuses healthStatuses;

    @Mock
    private AppVersion appVersion;

    @Mock
    private BlocklistStore blocklistStore;

    @Mock
    private RedisKeyCounter redisKeyCounter;

    @Mock
    private SystemStatsRepository systemStatsRepository;

    @Mock
    private ShortUrlUniqueIndex shortUrlUniqueIndex;

    @Mock
    private HourlyCountRepository hourlyCountRepository;

    @Mock
    private Environment environment;

    private SystemStats systemStats;

    @BeforeEach
    void setUp() {
        final var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final var appProperties = new AppProperties();
        appProperties.getAnalytics().setZone(ZoneId.of("Europe/Zagreb"));
        final var statsDays = new StatsDays(new AnalyticsDays(clock, appProperties), clock, ZoneOffset.UTC);
        this.systemStats = new SystemStats(runtimeMetrics, healthStatuses, appVersion, blocklistStore, redisKeyCounter,
                systemStatsRepository, shortUrlUniqueIndex, hourlyCountRepository, new DailyBuckets(statsDays), environment);

        lenient().when(runtimeMetrics.heap()).thenReturn(new JvmHeap(0, 0));
        lenient().when(runtimeMetrics.startedAt()).thenReturn(STARTED_AT);
        lenient().when(runtimeMetrics.uptime()).thenReturn(Duration.ofSeconds(30));
        lenient().when(runtimeMetrics.redirectTimings()).thenReturn(NO_REDIRECTS);
        lenient().when(runtimeMetrics.requestCounts()).thenReturn(new RequestCounts(0, 0));
        lenient().when(runtimeMetrics.urlCacheStats()).thenReturn(CacheStats.of(0, 0));
        lenient().when(appVersion.current()).thenReturn("2.9");
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        lenient().when(systemStatsRepository.databaseInfo()).thenReturn(POSTGRES);
        lenient().when(blocklistStore.memory()).thenReturn(new RedisMemory(0, 0));
        lenient().when(redisKeyCounter.count()).thenReturn(noKeys());
    }

    @Test
    void reportsHealthVersionAndUniqueness() {
        when(healthStatuses.status("db")).thenReturn("UP");
        when(healthStatuses.status("redis")).thenReturn("UP");
        when(shortUrlUniqueIndex.enforced()).thenReturn(true);

        final var system = systemStats.collect(WINDOW);

        assertThat(system.health()).isEqualTo(new ComponentHealth("UP", "UP"));
        assertThat(system.appVersion()).isEqualTo("2.9");
        assertThat(system.database()).isEqualTo(new DatabaseStats("PostgreSQL", "18.0", 12_345_678L, true));
        assertThat(system.javaVersion()).isEqualTo(System.getProperty("java.version"));
        assertThat(system.activeProfiles()).containsExactly("prod");
    }

    @Test
    void redisDownStillAnswers(final CapturedOutput output) {
        final var refused = new RedisConnectionFailureException("Unable to connect to " + REDIS_ADDRESS);
        when(blocklistStore.memory()).thenThrow(refused);
        when(redisKeyCounter.count()).thenThrow(refused);
        when(healthStatuses.status("db")).thenReturn("UP");
        when(healthStatuses.status("redis")).thenReturn("DOWN");

        final var system = systemStats.collect(WINDOW);

        assertThat(system.redis()).isEqualTo(new RedisStats(null, null, null));
        assertThat(system.health()).isEqualTo(new ComponentHealth("UP", "DOWN"));
        assertThat(system).hasNoNullFieldsOrPropertiesExcept("redis");
        assertThat(system.database()).hasNoNullFieldsOrProperties();
        assertThat(system.emails().daily()).hasSize(7);
        assertThat(output)
                .contains("exception=" + RedisConnectionFailureException.class.getName())
                .doesNotContain(REDIS_ADDRESS);
    }

    @Test
    void redisMemoryAndKeysComeFromRedis() {
        final var keys = new RedisKeyCounts(countsOf(8, 20, 300, 50, 10, 0, 120), 3, 511, false);
        when(blocklistStore.memory()).thenReturn(new RedisMemory(52_428_800, 0));
        when(redisKeyCounter.count()).thenReturn(keys);

        assertThat(systemStats.collect(WINDOW).redis()).isEqualTo(new RedisStats(52_428_800L, 0L, keys));
    }

    @Test
    void redirectsAndRequestsComeFromRuntimeMetrics() {
        final var redirects = List.of(new RedirectTiming("cache", 1000, 2.1, 15.0), new RedirectTiming("db", 300, 8.4, 40.2));
        when(runtimeMetrics.redirectTimings()).thenReturn(redirects);
        when(runtimeMetrics.requestCounts()).thenReturn(new RequestCounts(50_000, 3));
        when(runtimeMetrics.urlCacheStats()).thenReturn(CacheStats.of(900, 100));
        when(runtimeMetrics.heap()).thenReturn(new JvmHeap(314_572_800, 1_073_741_824));
        when(runtimeMetrics.uptime()).thenReturn(Duration.ofHours(3).plusMinutes(12));

        final var system = systemStats.collect(WINDOW);

        assertThat(system.redirects()).isEqualTo(redirects);
        assertThat(system.requests()).isEqualTo(new RequestCounts(50_000, 3));
        assertThat(system.cache()).isEqualTo(new CacheStats(900, 100, 90.0));
        assertThat(system.jvmHeapUsedBytes()).isEqualTo(314_572_800);
        assertThat(system.jvmHeapMaxBytes()).isEqualTo(1_073_741_824);
        assertThat(system.uptime()).isEqualTo("3h 12m");
        assertThat(system.startedAt()).isEqualTo(LocalDateTime.ofInstant(STARTED_AT, ZoneId.systemDefault()));
    }

    @Test
    void emailsUseTheStatusKeys() {
        when(hourlyCountRepository.count(EMAILS_BY_STATUS, WINDOW.start(), WINDOW.end())).thenReturn(List.of(
                new HourlyCount(LocalDate.of(2026, 10, 24), 22, "SENT", 2),
                new HourlyCount(LocalDate.of(2026, 10, 25), 9, "FAILED", 1),
                new HourlyCount(LocalDate.of(2026, 10, 25), 10, "BOUNCED", 1)));

        final var emails = systemStats.collect(WINDOW).emails();

        assertThat(emails.keys()).containsExactly("SENT", "FAILED", "SENDING", "RESENT", "BOUNCED");
        assertThat(emails.daily()).filteredOn(day -> day.date().equals(LocalDate.of(2026, 10, 25))).singleElement()
                .extracting(DailyCounts::counts)
                .satisfies(counts -> assertThat(counts).containsExactly(
                        entry("SENT", 2L), entry("FAILED", 1L), entry("SENDING", 0L), entry("RESENT", 0L), entry("BOUNCED", 1L)));
    }

    @Test
    void emptySiteIsAllZeros() {
        final var system = systemStats.collect(WINDOW);

        assertThat(system.days()).isEqualTo(7);
        assertThat(system.from()).isEqualTo(FROM);
        assertThat(system.to()).isEqualTo(TO);
        assertThat(system.emails().keys()).containsExactlyElementsOf(STATUSES);
        assertThat(system.emails().daily()).extracting(DailyCounts::date)
                .containsExactlyElementsOf(FROM.datesUntil(TO.plusDays(1)).toList());
        assertThat(system.emails().daily()).allSatisfy(day -> {
            assertThat(day.counts().keySet()).containsExactlyElementsOf(STATUSES);
            assertThat(day.counts().values()).containsOnly(0L);
        });
        assertThat(system.cache()).isEqualTo(new CacheStats(0, 0, null));
        assertThat(system.requests()).isEqualTo(new RequestCounts(0, 0));
        assertThat(system.redirects()).isEqualTo(NO_REDIRECTS);
        assertThat(system.redis()).isEqualTo(new RedisStats(0L, 0L, noKeys()));
        assertThat(system.database().shortUrlUnique()).isFalse();
        verify(hourlyCountRepository).count(EMAILS_BY_STATUS, WINDOW.start(), WINDOW.end());
    }

    private static RedisKeyCounts noKeys() {
        return new RedisKeyCounts(countsOf(0, 0, 0, 0, 0, 0, 0), 0, 0, false);
    }

    private static Map<String, Long> countsOf(final long... counts) {
        final var byPrefix = new LinkedHashMap<String, Long>();
        for (int index = 0; index < PREFIXES.size(); index++) {
            byPrefix.put(PREFIXES.get(index), counts[index]);
        }
        return byPrefix;
    }

}
