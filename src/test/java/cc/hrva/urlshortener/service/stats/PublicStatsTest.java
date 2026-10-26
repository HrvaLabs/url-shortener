package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.BlockedLinks;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.Blocklist;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.DailyClicks;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.stats.HourlyCount;
import cc.hrva.urlshortener.repository.stats.HourlyCountRepository;
import cc.hrva.urlshortener.repository.stats.LinksStatsRepository;
import cc.hrva.urlshortener.repository.stats.LinksStatsRepository.CreatedCounts;
import cc.hrva.urlshortener.repository.stats.LinksStatsRepository.InventoryCounts;
import cc.hrva.urlshortener.repository.stats.SafetyStatsRepository;
import cc.hrva.urlshortener.repository.stats.TrafficStatsRepository;
import cc.hrva.urlshortener.service.BlocklistSourceService;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cc.hrva.urlshortener.repository.stats.HourlySeries.BLOCKS_BY_REASON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicStatsTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 26);
    private static final LocalDate TO = LocalDate.of(2026, 10, 25);
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 25, 22, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 25, 23, 0);
    private static final LocalDateTime STORED_NOW = LocalDateTime.of(2026, 10, 26, 10, 0);

    @Mock
    private TrafficStatsRepository trafficStatsRepository;

    @Mock
    private LinksStatsRepository linksStatsRepository;

    @Mock
    private HourlyCountRepository hourlyCountRepository;

    @Mock
    private SafetyStatsRepository safetyStatsRepository;

    @Mock
    private BlocklistSourceService blocklistSourceService;

    @BeforeEach
    void setUp() {
        lenient().when(trafficStatsRepository.findDaily(any(), any())).thenReturn(List.of());
        lenient().when(linksStatsRepository.countInventory(any())).thenReturn(new InventoryCounts(0, 0, 0, 0, 0, 0));
        lenient().when(linksStatsRepository.countCreated(any(), any())).thenReturn(new CreatedCounts(0, 0, 0));
        lenient().when(linksStatsRepository.countBlockedByReason()).thenReturn(List.of());
        lenient().when(hourlyCountRepository.count(any(), any(), any())).thenReturn(List.of());
        lenient().when(safetyStatsRepository.findResolveMinutes(any(), any())).thenReturn(List.of());
        lenient().when(safetyStatsRepository.countResolvedVisitorReports(any(), any())).thenReturn(0L);
        lenient().when(blocklistSourceService.list()).thenReturn(List.of());
    }

    @Test
    void coversTheThirtyFullDaysEndingYesterday() {
        final var stats = publicStatsAt(NOW).collect();

        assertThat(stats.from()).isEqualTo(FROM);
        assertThat(stats.to()).isEqualTo(TO);
        assertThat(stats.updatedAt()).isEqualTo(STORED_NOW);
        verify(trafficStatsRepository).findDaily(FROM, TO);
        verify(linksStatsRepository).countCreated(START, END);
        verify(linksStatsRepository).countInventory(STORED_NOW);
        verify(hourlyCountRepository).count(BLOCKS_BY_REASON, START, END);
        verify(safetyStatsRepository).findResolveMinutes(START, END);
        verify(safetyStatsRepository).countResolvedVisitorReports(START, END);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-25T21:59:59Z, 2026-09-25, 2026-10-24",
            "2026-10-25T22:00:00Z, 2026-09-25, 2026-10-24",
            "2026-10-25T23:00:00Z, 2026-09-26, 2026-10-25",
            "2026-03-29T00:30:00Z, 2026-02-27, 2026-03-28"
    })
    void theDaysAreZagrebDays(final Instant now, final LocalDate from, final LocalDate to) {
        final var stats = publicStatsAt(now).collect();

        assertThat(stats.from()).isEqualTo(from);
        assertThat(stats.to()).isEqualTo(to);
        assertThat(stats.daily()).hasSize(30);
    }

    @Test
    void dailyHasThirtyZeroFilledDaysOfClicksOnly() {
        when(trafficStatsRepository.findDaily(FROM, TO)).thenReturn(List.of(
                new AnalyticsDay(FROM, 30, 12, 400),
                new AnalyticsDay(LocalDate.of(2026, 10, 14), 50, 20, 7),
                new AnalyticsDay(TO, 20, 9, 1)));

        final var stats = publicStatsAt(NOW).collect();

        assertThat(stats.daily()).hasSize(30);
        assertThat(stats.daily().getFirst()).isEqualTo(new DailyClicks(FROM, 30));
        assertThat(stats.daily().get(18)).isEqualTo(new DailyClicks(LocalDate.of(2026, 10, 14), 50));
        assertThat(stats.daily().get(1)).isEqualTo(new DailyClicks(FROM.plusDays(1), 0));
        assertThat(stats.daily().getLast()).isEqualTo(new DailyClicks(TO, 20));
        assertThat(stats.daily()).extracting(DailyClicks::date).isEqualTo(FROM.datesUntil(TO.plusDays(1)).toList());
        assertThat(stats.clicks()).isEqualTo(100);
    }

    @Test
    void activeLinksAndLinksCreatedComeFromTheLinkCounts() {
        when(linksStatsRepository.countInventory(STORED_NOW)).thenReturn(new InventoryCounts(900, 700, 12, 30, 4, 50));
        when(linksStatsRepository.countCreated(START, END)).thenReturn(new CreatedCounts(40, 15, 3));

        final var stats = publicStatsAt(NOW).collect();

        assertThat(stats.activeLinks()).isEqualTo(700);
        assertThat(stats.linksCreated()).isEqualTo(40);
    }

    @Test
    void blockedCountsAllTimeAndTheWindow() {
        when(linksStatsRepository.countBlockedByReason()).thenReturn(List.of(new CountEntry("BLOCKLIST", 8), new CountEntry("ADMIN", 4)));
        when(hourlyCountRepository.count(BLOCKS_BY_REASON, START, END)).thenReturn(List.of(
                new HourlyCount(LocalDate.of(2026, 10, 1), 9, "BLOCKLIST", 2),
                new HourlyCount(LocalDate.of(2026, 10, 20), 14, "SAFE_BROWSING", 1)));

        final var stats = publicStatsAt(NOW).collect();

        assertThat(stats.blocked()).isEqualTo(new BlockedLinks(12, 3));
    }

    @Test
    void visitorReportsHandledAreTheResolvedVisitorReportsInTheWindow() {
        when(safetyStatsRepository.countResolvedVisitorReports(START, END)).thenReturn(4L);

        assertThat(publicStatsAt(NOW).collect().visitorReportsHandled()).isEqualTo(4);
    }

    @Test
    void averageReviewTimeIsTheRoundedMean() {
        when(safetyStatsRepository.findResolveMinutes(START, END)).thenReturn(List.of(10L, 20L, 31L), List.of(10L, 11L), List.of(), List.of(0L));

        final var publicStats = publicStatsAt(NOW);

        assertThat(publicStats.collect().averageReviewMinutes()).isEqualTo(20L);
        assertThat(publicStats.collect().averageReviewMinutes()).isEqualTo(11L);
        assertThat(publicStats.collect().averageReviewMinutes()).isNull();
        assertThat(publicStats.collect().averageReviewMinutes()).isZero();
    }

    @Test
    void blocklistsAreEnabledAndLoadedOnlyWithoutUrls() {
        when(blocklistSourceService.list()).thenReturn(List.of(
                source(1L, "URLhaus", BlocklistAction.BLOCK, true, 120_000L),
                source(2L, "Disabled list", BlocklistAction.BLOCK, false, 500_000L),
                source(3L, "Never loaded", BlocklistAction.FLAG, true, null),
                source(4L, "Empty list", BlocklistAction.FLAG, true, 0L),
                source(5L, "HaGeZi TIF medium", BlocklistAction.BLOCK, true, 400_000L),
                source(6L, "Abuse feed", BlocklistAction.FLAG, true, 120_000L)));

        final var stats = publicStatsAt(NOW).collect();

        assertThat(stats.blocklists()).containsExactly(
                new Blocklist("HaGeZi TIF medium", BlocklistAction.BLOCK, 400_000),
                new Blocklist("Abuse feed", BlocklistAction.FLAG, 120_000),
                new Blocklist("URLhaus", BlocklistAction.BLOCK, 120_000));
        assertThat(stats.toString()).doesNotContain("https://", "secret-key", "HTTP 403");
    }

    @Test
    void aFreshSiteIsAllZeros() {
        final var stats = publicStatsAt(NOW).collect();

        assertThat(stats).isEqualTo(new PublicStatsResponse(
                FROM,
                TO,
                0,
                0,
                0,
                FROM.datesUntil(TO.plusDays(1)).map(day -> new DailyClicks(day, 0)).toList(),
                new BlockedLinks(0, 0),
                0,
                null,
                List.of(),
                STORED_NOW));
    }

    @Test
    void theAnswerHasNoPerLinkOrPersonalField() {
        assertThat(componentNames(PublicStatsResponse.class)).containsExactly("from", "to", "activeLinks", "linksCreated", "clicks", "daily",
                "blocked", "visitorReportsHandled", "averageReviewMinutes", "blocklists", "updatedAt");
        assertThat(componentNames(DailyClicks.class)).containsExactly("date", "clicks");
        assertThat(componentNames(BlockedLinks.class)).containsExactly("allTime", "last30Days");
        assertThat(componentNames(Blocklist.class)).containsExactly("name", "action", "entries");
        assertThat(Stream.of(PublicStatsResponse.class, DailyClicks.class, BlockedLinks.class, Blocklist.class)
                .flatMap(type -> componentNames(type).stream())
                .map(name -> name.toLowerCase(Locale.ROOT)))
                .noneMatch(name -> Stream.of("shorturl", "alias", "owner", "email", "host", "country", "referrer", "url", "lasterror", "id")
                        .anyMatch(name::contains));
    }

    private PublicStats publicStatsAt(final Instant now) {
        final var clock = Clock.fixed(now, ZoneOffset.UTC);
        final var appProperties = new AppProperties();
        appProperties.getAnalytics().setZone(ZoneId.of("Europe/Zagreb"));
        final var analyticsDays = new AnalyticsDays(clock, appProperties);
        return new PublicStats(new StatsDays(analyticsDays, clock, ZoneOffset.UTC), analyticsDays, trafficStatsRepository, linksStatsRepository,
                hourlyCountRepository, safetyStatsRepository, blocklistSourceService);
    }

    private static List<String> componentNames(final Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static BlocklistSourceResponse source(final Long id, final String name, final BlocklistAction action, final boolean enabled,
            final Long domainCount) {
        return new BlocklistSourceResponse(id, name, "https://lists.example/" + id + "?key=secret-key", List.of("https://mirror.example/" + id),
                SourceKind.DOMAINS, action, enabled, 6, LocalDateTime.of(2026, 10, 26, 6, 0), LocalDateTime.of(2026, 10, 26, 6, 0),
                "https://lists.example/" + id, domainCount, "v1", "HTTP 403 from https://lists.example/" + id, 2_048_000L);
    }

}
