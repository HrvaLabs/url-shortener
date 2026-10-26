package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.WeeklyDigest.Change;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.DailyCounts;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.CreatedInRange;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.LinkInventory;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse.DomainRuleCounts;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse.ResolveTimes;
import cc.hrva.urlshortener.dto.stats.StackedSeries;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.ApiKeyCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.McpCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.UserInventory;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.impl.DefaultWeeklyDigestService;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultWeeklyDigestServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final LocalDate WEEK_FROM = LocalDate.of(2026, 10, 19);
    private static final LocalDate WEEK_TO = LocalDate.of(2026, 10, 25);
    private static final LocalDate BEFORE_FROM = LocalDate.of(2026, 10, 12);
    private static final LocalDate BEFORE_TO = LocalDate.of(2026, 10, 18);
    private static final List<String> CHANNELS = List.of("WEB", "API_KEY", "BULK", "MCP", "ANONYMOUS_WEB", "ANONYMOUS_MCP", "unknown");
    private static final List<String> PROVIDERS = List.of("local", "google", "github");
    private static final List<String> REVIEW_SOURCES = List.of("SYSTEM", "VISITOR");
    private static final List<String> BLOCK_REASONS =
            List.of("SAFE_BROWSING", "BLOCKLIST", "DOMAIN_RULE", "ADMIN", "USER_BLOCKED", "SCAM_SIGNALS");
    private static final StackedSeries NO_SERIES = new StackedSeries(List.of(), List.of());

    @Mock
    private AdminStatsService adminStatsService;

    @Mock
    private AnalyticsDays analyticsDays;

    private WeeklyDigestService weeklyDigestService;

    @BeforeEach
    void setUp() {
        this.weeklyDigestService = new DefaultWeeklyDigestService(adminStatsService, analyticsDays);
    }

    @Test
    void reportsTheLastClosedWeekAndTheOneBefore() {
        stubWeek(traffic(WEEK_FROM, WEEK_TO, 0, 0, 0, List.of()), links(WEEK_FROM, WEEK_TO, NO_SERIES, 0),
                users(WEEK_FROM, WEEK_TO, NO_SERIES, 0), safety(NO_SERIES, List.of(), NO_SERIES));
        stubWeekBefore(traffic(BEFORE_FROM, BEFORE_TO, 0, 0, 0, List.of()), links(BEFORE_FROM, BEFORE_TO, NO_SERIES, 0),
                users(BEFORE_FROM, BEFORE_TO, NO_SERIES, 0));

        final var digest = weeklyDigestService.collect(TODAY);

        assertThat(digest.from()).isEqualTo(WEEK_FROM);
        assertThat(digest.to()).isEqualTo(WEEK_TO);
        verify(adminStatsService).traffic(WEEK_FROM, WEEK_TO);
        verify(adminStatsService).links(WEEK_FROM, WEEK_TO);
        verify(adminStatsService).users(WEEK_FROM, WEEK_TO);
        verify(adminStatsService).safety(WEEK_FROM, WEEK_TO);
        verify(adminStatsService).traffic(BEFORE_FROM, BEFORE_TO);
        verify(adminStatsService).links(BEFORE_FROM, BEFORE_TO);
        verify(adminStatsService).users(BEFORE_FROM, BEFORE_TO);
        verifyNoMoreInteractions(adminStatsService);
    }

    @Test
    void buildsEveryFigureFromTheAdminStats() {
        stubWeek(
                traffic(WEEK_FROM, WEEK_TO, 1204, 950, 301, List.of(topLink(1L, "menu", "example.com", 120))),
                links(WEEK_FROM, WEEK_TO, series(CHANNELS, new long[] {20, 2, 0, 1, 0, 0, 0}, new long[] {10, 4, 0, 3, 0, 0, 0}), 40),
                users(WEEK_FROM, WEEK_TO, series(PROVIDERS, new long[] {2, 0, 0}, new long[] {0, 1, 0}), 12),
                safety(
                        series(REVIEW_SOURCES, new long[] {3, 1}, new long[] {0, 1}),
                        List.of(new CountEntry("OK", 3), new CountEntry("BLOCK_LINK", 1)),
                        series(BLOCK_REASONS, new long[] {0, 2, 0, 0, 0, 0}, new long[] {1, 0, 0, 0, 0, 0})));
        stubWeekBefore(
                traffic(BEFORE_FROM, BEFORE_TO, 1075, 900, 10, List.of()),
                links(BEFORE_FROM, BEFORE_TO, series(CHANNELS, new long[] {42, 0, 0, 0, 0, 0, 0}), 42),
                users(BEFORE_FROM, BEFORE_TO, series(PROVIDERS, new long[] {0, 0, 0}), 3));

        assertThat(weeklyDigestService.collect(TODAY)).isEqualTo(new WeeklyDigest(
                WEEK_FROM,
                WEEK_TO,
                new Change(1204, 1075),
                950,
                301,
                new Change(40, 42),
                List.of(new CountEntry("WEB", 30), new CountEntry("API_KEY", 6), new CountEntry("MCP", 4)),
                new Change(3, 0),
                12,
                5,
                4,
                List.of(new CountEntry("BLOCKLIST", 2), new CountEntry("SAFE_BROWSING", 1)),
                List.of(new WeeklyDigest.TopLink("menu", "example.com", 120))));
    }

    @Test
    void onlyAutomaticBlocksAreCounted() {
        stubEmptyWeekWith(
                links(WEEK_FROM, WEEK_TO, NO_SERIES, 0),
                safety(NO_SERIES, List.of(), series(BLOCK_REASONS, new long[] {0, 1, 0, 3, 2, 0}, new long[] {1, 1, 0, 1, 0, 0})),
                List.of());

        assertThat(weeklyDigestService.collect(TODAY).automaticBlocks())
                .containsExactly(new CountEntry("BLOCKLIST", 2), new CountEntry("SAFE_BROWSING", 1));
    }

    @Test
    void channelsWithoutLinksAreLeftOut() {
        stubEmptyWeekWith(
                links(WEEK_FROM, WEEK_TO, series(CHANNELS, new long[] {1, 0, 2, 2, 0, 0, 0}, new long[] {2, 0, 1, 3, 0, 0, 0}), 11),
                safety(NO_SERIES, List.of(), NO_SERIES),
                List.of());

        assertThat(weeklyDigestService.collect(TODAY).linksByChannel())
                .containsExactly(new CountEntry("MCP", 5), new CountEntry("WEB", 3), new CountEntry("BULK", 3));
    }

    @Test
    void topLinksAreTheFiveBusiestWithoutOwners() {
        final var busiest = LongStream.rangeClosed(1, 7)
                .mapToObj(rank -> topLink(rank, "link" + rank, "site" + rank + ".example", 100 - rank))
                .toList();
        stubEmptyWeekWith(links(WEEK_FROM, WEEK_TO, NO_SERIES, 0), safety(NO_SERIES, List.of(), NO_SERIES), busiest);

        assertThat(weeklyDigestService.collect(TODAY).topLinks()).containsExactly(
                new WeeklyDigest.TopLink("link1", "site1.example", 99),
                new WeeklyDigest.TopLink("link2", "site2.example", 98),
                new WeeklyDigest.TopLink("link3", "site3.example", 97),
                new WeeklyDigest.TopLink("link4", "site4.example", 96),
                new WeeklyDigest.TopLink("link5", "site5.example", 95));
        assertThat(Arrays.stream(WeeklyDigest.TopLink.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("shortUrl", "destinationHost", "clicks");
    }

    private void stubWeek(final TrafficStatsResponse traffic, final LinksStatsResponse links, final UsersStatsResponse users,
                          final SafetyStatsResponse safety) {
        when(adminStatsService.traffic(WEEK_FROM, WEEK_TO)).thenReturn(traffic);
        when(adminStatsService.links(WEEK_FROM, WEEK_TO)).thenReturn(links);
        when(adminStatsService.users(WEEK_FROM, WEEK_TO)).thenReturn(users);
        when(adminStatsService.safety(WEEK_FROM, WEEK_TO)).thenReturn(safety);
    }

    private void stubWeekBefore(final TrafficStatsResponse traffic, final LinksStatsResponse links, final UsersStatsResponse users) {
        when(adminStatsService.traffic(BEFORE_FROM, BEFORE_TO)).thenReturn(traffic);
        when(adminStatsService.links(BEFORE_FROM, BEFORE_TO)).thenReturn(links);
        when(adminStatsService.users(BEFORE_FROM, BEFORE_TO)).thenReturn(users);
    }

    private void stubEmptyWeekWith(final LinksStatsResponse links, final SafetyStatsResponse safety,
                                   final List<TrafficStatsResponse.TopLink> topLinks) {
        stubWeek(traffic(WEEK_FROM, WEEK_TO, 0, 0, 0, topLinks), links, users(WEEK_FROM, WEEK_TO, NO_SERIES, 0), safety);
        stubWeekBefore(traffic(BEFORE_FROM, BEFORE_TO, 0, 0, 0, List.of()), links(BEFORE_FROM, BEFORE_TO, NO_SERIES, 0),
                users(BEFORE_FROM, BEFORE_TO, NO_SERIES, 0));
    }

    private static TrafficStatsResponse.TopLink topLink(final long id, final String shortUrl, final String host, final long clicks) {
        return new TrafficStatsResponse.TopLink(id, shortUrl, host, "owner" + id + "@example.com", clicks, clicks / 2);
    }

    private static TrafficStatsResponse traffic(final LocalDate from, final LocalDate to, final long clicks, final long uniqueVisitors,
                                                final long botClicks, final List<TrafficStatsResponse.TopLink> topLinks) {
        return new TrafficStatsResponse(7, from, to, new AnalyticsTotals(clicks, uniqueVisitors, botClicks), List.of(), 0, clicks,
                topLinks, new Breakdowns(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), 5000);
    }

    private static LinksStatsResponse links(final LocalDate from, final LocalDate to, final StackedSeries created, final long createdTotal) {
        return new LinksStatsResponse(7, from, to, created, new CreatedInRange(createdTotal, createdTotal, 0, 0),
                new LinkInventory(500, 480, 20, 3, 4, 5, 6, List.of(new CountEntry("ADMIN", 9))), List.of(), List.of(), List.of());
    }

    private static UsersStatsResponse users(final LocalDate from, final LocalDate to, final StackedSeries signups, final long activeLast7Days) {
        return new UsersStatsResponse(7, from, to, signups, new UserInventory(80, 78, 2, 1, 70, 10, List.of()), activeLast7Days, 40,
                new ApiKeyCounts(0, 0, 0, 0), new McpCounts(0, 0));
    }

    private static SafetyStatsResponse safety(final StackedSeries reviewsOpened, final List<CountEntry> resolved,
                                              final StackedSeries blocked) {
        return new SafetyStatsResponse(7, WEEK_FROM, WEEK_TO, reviewsOpened, 6, 2, resolved, new ResolveTimes(0, null, null), blocked,
                new DomainRuleCounts(0, 0), 0, List.of());
    }

    private static StackedSeries series(final List<String> keys, final long[]... days) {
        final var daily = IntStream.range(0, days.length)
                .mapToObj(day -> new DailyCounts(WEEK_FROM.plusDays(day), countsOf(keys, days[day])))
                .toList();
        return new StackedSeries(keys, daily);
    }

    private static Map<String, Long> countsOf(final List<String> keys, final long[] counts) {
        final var byKey = new LinkedHashMap<String, Long>();
        IntStream.range(0, keys.size()).forEach(index -> byKey.put(keys.get(index), counts[index]));
        return byKey;
    }

}
