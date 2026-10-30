package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.DailyCounts;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse.DomainRuleCounts;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse.ResolveTimes;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse.SourceHealth;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.stats.HourlyCount;
import cc.hrva.urlshortener.repository.stats.HourlyCountRepository;
import cc.hrva.urlshortener.repository.stats.SafetyStatsRepository;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.LongStream;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cc.hrva.urlshortener.repository.stats.HourlySeries.BLOCKS_BY_REASON;
import static cc.hrva.urlshortener.repository.stats.HourlySeries.REVIEWS_BY_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SafetyStatsTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 10, 20);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);
    private static final StatsWindow WINDOW =
            new StatsWindow(7, FROM, TO, LocalDateTime.of(2026, 10, 19, 22, 0), LocalDateTime.of(2026, 10, 26, 23, 0));
    private static final List<String> SOURCES = List.of("SYSTEM", "VISITOR");
    private static final List<String> REASONS = List.of("SAFE_BROWSING", "BLOCKLIST", "DOMAIN_RULE", "ADMIN", "USER_BLOCKED", "SCAM_SIGNALS", "CAMPAIGN");
    private static final LocalDateTime LAST_SUCCESS = LocalDateTime.of(2026, 10, 26, 6, 0);
    private static final String URLHAUS_KEY = "7f3a9c2e5b8d4f1a";
    private static final String URLHAUS_URL = "https://urlhaus-api.abuse.ch/v2/files/exports/" + URLHAUS_KEY + "/text_online";
    private static final String FALLBACK_URL = "https://urlhaus.abuse.ch/downloads/text_online;auth-key=" + URLHAUS_KEY;
    private static final String LAST_SUCCESS_URL = "https://mirror.abuse.ch/urlhaus/" + URLHAUS_KEY + "/text_online";

    @Mock
    private SafetyStatsRepository safetyStatsRepository;

    @Mock
    private HourlyCountRepository hourlyCountRepository;

    @Mock
    private LinkReviewRepository linkReviewRepository;

    @Mock
    private DomainRuleRepository domainRuleRepository;

    @Mock
    private ScammerSignalService scammerSignalService;

    @Mock
    private BlocklistSourceService blocklistSourceService;

    private SafetyStats safetyStats;

    @BeforeEach
    void setUp() {
        final var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final var appProperties = new AppProperties();
        appProperties.getAnalytics().setZone(ZoneId.of("Europe/Zagreb"));
        final var statsDays = new StatsDays(new AnalyticsDays(clock, appProperties), clock, ZoneOffset.UTC);
        this.safetyStats = new SafetyStats(safetyStatsRepository, hourlyCountRepository, new DailyBuckets(statsDays), linkReviewRepository,
                domainRuleRepository, scammerSignalService, blocklistSourceService);
    }

    @Test
    void percentilesAreNearestRank() {
        final var tenToHundred = LongStream.rangeClosed(1, 10).map(step -> step * 10).boxed().toList();
        when(safetyStatsRepository.findResolveMinutes(WINDOW.start(), WINDOW.end())).thenReturn(tenToHundred);

        assertThat(safetyStats.collect(WINDOW).resolveTimes()).isEqualTo(new ResolveTimes(10, 50L, 90L));
        assertThat(SafetyStats.nearestRank(tenToHundred, 0.5)).isEqualTo(50L);
        assertThat(SafetyStats.nearestRank(tenToHundred, 0.9)).isEqualTo(90L);
        assertThat(SafetyStats.nearestRank(List.of(15L), 0.5)).isEqualTo(15L);
        assertThat(SafetyStats.nearestRank(List.of(15L), 0.9)).isEqualTo(15L);
    }

    @Test
    void noResolvedReviewsHaveNoTimes() {
        when(safetyStatsRepository.findResolveMinutes(WINDOW.start(), WINDOW.end())).thenReturn(List.of());

        assertThat(safetyStats.collect(WINDOW).resolveTimes()).isEqualTo(new ResolveTimes(0, null, null));
        assertThat(SafetyStats.nearestRank(List.of(), 0.5)).isNull();
    }

    @Test
    void resolvedComesFromTheWindow() {
        when(safetyStatsRepository.countResolvedByResolution(WINDOW.start(), WINDOW.end()))
                .thenReturn(List.of(new CountEntry("OK", 10), new CountEntry("LINK_BLOCKED", 4)));

        assertThat(safetyStats.collect(WINDOW).resolved())
                .containsExactly(new CountEntry("OK", 10), new CountEntry("LINK_BLOCKED", 4));
    }

    @Test
    void seriesUseTheSourceAndReasonKeys() {
        when(hourlyCountRepository.count(REVIEWS_BY_SOURCE, WINDOW.start(), WINDOW.end())).thenReturn(List.of(
                new HourlyCount(LocalDate.of(2026, 10, 24), 22, "SYSTEM", 2),
                new HourlyCount(LocalDate.of(2026, 10, 25), 9, "VISITOR", 1)));
        when(hourlyCountRepository.count(BLOCKS_BY_REASON, WINDOW.start(), WINDOW.end())).thenReturn(List.of(
                new HourlyCount(LocalDate.of(2026, 10, 25), 8, "BLOCKLIST", 3),
                new HourlyCount(LocalDate.of(2026, 10, 25), 9, "CAMPAIGN", 4),
                new HourlyCount(LocalDate.of(2026, 10, 25), 20, "unknown", 1)));

        final var safety = safetyStats.collect(WINDOW);

        assertThat(safety.reviewsOpened().keys()).containsExactlyElementsOf(SOURCES);
        assertThat(safety.reviewsOpened().daily()).filteredOn(day -> day.date().equals(LocalDate.of(2026, 10, 25))).singleElement()
                .extracting(DailyCounts::counts)
                .satisfies(counts -> assertThat(counts).containsExactly(entry("SYSTEM", 2L), entry("VISITOR", 1L)));
        assertThat(safety.blocked().keys()).containsExactly(
                "SAFE_BROWSING", "BLOCKLIST", "DOMAIN_RULE", "ADMIN", "USER_BLOCKED", "SCAM_SIGNALS", "CAMPAIGN", "unknown");
        assertThat(safety.blocked().daily()).filteredOn(day -> day.date().equals(LocalDate.of(2026, 10, 25))).singleElement()
                .extracting(DailyCounts::counts)
                .satisfies(counts -> assertThat(counts).containsExactly(entry("SAFE_BROWSING", 0L), entry("BLOCKLIST", 3L),
                        entry("DOMAIN_RULE", 0L), entry("ADMIN", 0L), entry("USER_BLOCKED", 0L), entry("SCAM_SIGNALS", 0L),
                        entry("CAMPAIGN", 4L), entry("unknown", 1L)));
    }

    @Test
    void sourcesLeaveOutTheUrl() {
        when(blocklistSourceService.list()).thenReturn(List.of(
                source(1L, "HaGeZi TIF medium", BlocklistAction.BLOCK, true, 120000L, 9000000L, null),
                source(6L, "URLhaus", BlocklistAction.BLOCK, false, null, null, null)));

        assertThat(Arrays.stream(SourceHealth.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("id", "name", "action", "enabled", "domainCount", "memoryBytes", "lastSuccessAt", "lastError")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("url"));
        assertThat(safetyStats.collect(WINDOW).sources()).containsExactly(
                new SourceHealth(1L, "HaGeZi TIF medium", BlocklistAction.BLOCK, true, 120000L, 9000000L, LAST_SUCCESS, null),
                new SourceHealth(6L, "URLhaus", BlocklistAction.BLOCK, false, null, null, LAST_SUCCESS, null));
    }

    @Test
    void sourceErrorsNeverShowTheSourceUrl() {
        when(blocklistSourceService.list()).thenReturn(List.of(source(6L, "URLhaus", BlocklistAction.BLOCK, true, 4200L, 840000L,
                "main: HTTP 410 for " + URLHAUS_URL + "; fallback 1: timed out after HTTPS://URLHAUS.ABUSE.CH/downloads/text_online;auth-key="
                        + URLHAUS_KEY)));

        final var lastError = safetyStats.collect(WINDOW).sources().getFirst().lastError();

        assertThat(lastError).isEqualTo("main: HTTP 410 for [url]; fallback 1: timed out after [url]");
        assertThat(lastError).doesNotContain(URLHAUS_KEY).doesNotContainIgnoringCase("abuse.ch");
    }

    @Test
    void sourceErrorsNeverShowTheSourceUrlWithoutItsScheme() {
        when(blocklistSourceService.list()).thenReturn(List.of(source(6L, "URLhaus", BlocklistAction.BLOCK, true, 4200L, 840000L,
                "main: could not read " + withoutScheme(URLHAUS_URL) + "; fallback 1: HTTP 503 from "
                        + withoutScheme(FALLBACK_URL).toUpperCase(Locale.ROOT) + "; last good copy " + withoutScheme(LAST_SUCCESS_URL)
                        + " is stale")));

        final var lastError = safetyStats.collect(WINDOW).sources().getFirst().lastError();

        assertThat(lastError).isEqualTo("main: could not read [url]; fallback 1: HTTP 503 from [url]; last good copy [url] is stale");
        assertThat(lastError).doesNotContainIgnoringCase(URLHAUS_KEY).doesNotContainIgnoringCase("abuse.ch");
    }

    @Test
    void openCountsAndScammersComeFromTheExistingQueries() {
        when(linkReviewRepository.countByResolvedAtIsNull()).thenReturn(5L);
        when(linkReviewRepository.countByResolvedAtIsNullAndReasonsContaining("reported by a visitor")).thenReturn(2L);
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of(27L, 31L));

        final var safety = safetyStats.collect(WINDOW);

        assertThat(safety.openReviews()).isEqualTo(5);
        assertThat(safety.openVisitorReports()).isEqualTo(2);
        assertThat(safety.possibleScammers()).isEqualTo(2);
    }

    @Test
    void domainRuleCountsByType() {
        when(domainRuleRepository.countByType(DomainRuleType.BLOCK)).thenReturn(4L);
        when(domainRuleRepository.countByType(DomainRuleType.ALLOW)).thenReturn(2L);

        assertThat(safetyStats.collect(WINDOW).domainRules()).isEqualTo(new DomainRuleCounts(4, 2));
    }

    @Test
    void emptySiteIsAllZeros() {
        final var safety = safetyStats.collect(WINDOW);

        assertThat(safety.days()).isEqualTo(7);
        assertThat(safety.from()).isEqualTo(FROM);
        assertThat(safety.to()).isEqualTo(TO);
        assertThat(safety.reviewsOpened().keys()).containsExactlyElementsOf(SOURCES);
        assertThat(safety.reviewsOpened().daily()).extracting(DailyCounts::date)
                .containsExactlyElementsOf(FROM.datesUntil(TO.plusDays(1)).toList());
        assertThat(safety.reviewsOpened().daily()).allSatisfy(day -> {
            assertThat(day.counts().keySet()).containsExactlyElementsOf(SOURCES);
            assertThat(day.counts().values()).containsOnly(0L);
        });
        assertThat(safety.blocked().keys()).containsExactlyElementsOf(REASONS);
        assertThat(safety.blocked().daily()).extracting(DailyCounts::date)
                .containsExactlyElementsOf(FROM.datesUntil(TO.plusDays(1)).toList());
        assertThat(safety.blocked().daily()).allSatisfy(day -> {
            assertThat(day.counts().keySet()).containsExactlyElementsOf(REASONS);
            assertThat(day.counts().values()).containsOnly(0L);
        });
        assertThat(safety.openReviews()).isZero();
        assertThat(safety.openVisitorReports()).isZero();
        assertThat(safety.resolved()).isEmpty();
        assertThat(safety.resolveTimes()).isEqualTo(new ResolveTimes(0, null, null));
        assertThat(safety.domainRules()).isEqualTo(new DomainRuleCounts(0, 0));
        assertThat(safety.possibleScammers()).isZero();
        assertThat(safety.sources()).isEmpty();
        verify(hourlyCountRepository).count(REVIEWS_BY_SOURCE, WINDOW.start(), WINDOW.end());
        verify(hourlyCountRepository).count(BLOCKS_BY_REASON, WINDOW.start(), WINDOW.end());
        verify(safetyStatsRepository).countResolvedByResolution(WINDOW.start(), WINDOW.end());
        verify(safetyStatsRepository).findResolveMinutes(WINDOW.start(), WINDOW.end());
    }

    private static BlocklistSourceResponse source(final Long id, final String name, final BlocklistAction action, final boolean enabled,
                                                  final Long domainCount, final Long memoryBytes, final String lastError) {
        return new BlocklistSourceResponse(id, name, URLHAUS_URL, List.of(FALLBACK_URL), SourceKind.LINKS, action, enabled, 1,
                LAST_SUCCESS, LAST_SUCCESS, LAST_SUCCESS_URL, domainCount, "2026-10-26", lastError, memoryBytes);
    }

    private static String withoutScheme(final String url) {
        return StringUtils.substringAfter(url, "://");
    }

}
