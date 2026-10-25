package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
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
import cc.hrva.urlshortener.exception.InvalidAnalyticsRangeException;
import cc.hrva.urlshortener.service.impl.DefaultAdminStatsService;
import cc.hrva.urlshortener.service.stats.LinksStats;
import cc.hrva.urlshortener.service.stats.SafetyStats;
import cc.hrva.urlshortener.service.stats.StatsDays;
import cc.hrva.urlshortener.service.stats.StatsWindow;
import cc.hrva.urlshortener.service.stats.TrafficStats;
import cc.hrva.urlshortener.service.stats.UsersStats;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cc.hrva.urlshortener.model.enums.AnalyticsRange.LAST_30_DAYS;
import static cc.hrva.urlshortener.model.enums.AnalyticsRange.LAST_7_DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAdminStatsServiceTest {

    private static final String RANGE_ERROR = "days must be 7, 30, 90 or 365";
    private static final StatsWindow LAST_7 = new StatsWindow(7, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 26),
            LocalDateTime.of(2026, 10, 19, 22, 0), LocalDateTime.of(2026, 10, 26, 23, 0));
    private static final StatsWindow LAST_30 = new StatsWindow(30, LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 26),
            LocalDateTime.of(2026, 9, 26, 22, 0), LocalDateTime.of(2026, 10, 26, 23, 0));

    @Mock
    private StatsDays statsDays;

    @Mock
    private TrafficStats trafficStats;

    @Mock
    private LinksStats linksStats;

    @Mock
    private UsersStats usersStats;

    @Mock
    private SafetyStats safetyStats;

    private AdminStatsService adminStatsService;

    @BeforeEach
    void setUp() {
        this.adminStatsService = new DefaultAdminStatsService(statsDays, trafficStats, linksStats, usersStats, safetyStats);
    }

    @Test
    void rejectsOtherRangesBeforeReadingAnything() {
        assertThatThrownBy(() -> adminStatsService.traffic(14))
                .isInstanceOf(InvalidAnalyticsRangeException.class)
                .hasMessage(RANGE_ERROR);
        assertThatThrownBy(() -> adminStatsService.links(14))
                .isInstanceOf(InvalidAnalyticsRangeException.class)
                .hasMessage(RANGE_ERROR);
        assertThatThrownBy(() -> adminStatsService.users(14))
                .isInstanceOf(InvalidAnalyticsRangeException.class)
                .hasMessage(RANGE_ERROR);
        assertThatThrownBy(() -> adminStatsService.safety(14))
                .isInstanceOf(InvalidAnalyticsRangeException.class)
                .hasMessage(RANGE_ERROR);

        verifyNoInteractions(statsDays, trafficStats, linksStats, usersStats, safetyStats);
    }

    @Test
    void defaultRangeIs30Days() {
        when(statsDays.window(LAST_30_DAYS)).thenReturn(LAST_30);
        when(trafficStats.collect(LAST_30)).thenReturn(emptyTraffic(LAST_30));

        assertThat(adminStatsService.traffic(null).days()).isEqualTo(30);
    }

    @Test
    void passesTheWindowToTheTrafficTab() {
        final var traffic = emptyTraffic(LAST_7);
        when(statsDays.window(LAST_7_DAYS)).thenReturn(LAST_7);
        when(trafficStats.collect(LAST_7)).thenReturn(traffic);

        assertThat(adminStatsService.traffic(7)).isSameAs(traffic);
    }

    @Test
    void passesTheWindowToTheLinksTab() {
        final var links = emptyLinks(LAST_7);
        when(statsDays.window(LAST_7_DAYS)).thenReturn(LAST_7);
        when(linksStats.collect(LAST_7)).thenReturn(links);

        assertThat(adminStatsService.links(7)).isSameAs(links);
    }

    @Test
    void passesTheWindowToTheUsersTab() {
        final var users = emptyUsers(LAST_7);
        when(statsDays.window(LAST_7_DAYS)).thenReturn(LAST_7);
        when(usersStats.collect(LAST_7)).thenReturn(users);

        assertThat(adminStatsService.users(7)).isSameAs(users);
    }

    @Test
    void passesTheWindowToTheSafetyTab() {
        final var safety = emptySafety(LAST_7);
        when(statsDays.window(LAST_7_DAYS)).thenReturn(LAST_7);
        when(safetyStats.collect(LAST_7)).thenReturn(safety);

        assertThat(adminStatsService.safety(7)).isSameAs(safety);
    }

    private static TrafficStatsResponse emptyTraffic(final StatsWindow window) {
        return new TrafficStatsResponse(window.days(), window.from(), window.to(), new AnalyticsTotals(0, 0, 0), List.of(), 0, 0,
                List.of(), new Breakdowns(List.of(), List.of(), List.of(), List.of(), List.of()), 0);
    }

    private static LinksStatsResponse emptyLinks(final StatsWindow window) {
        return new LinksStatsResponse(window.days(), window.from(), window.to(), new StackedSeries(List.of(), List.of()),
                new CreatedInRange(0, 0, 0, 0), new LinkInventory(0, 0, 0, 0, 0, 0, 0, List.of()), List.of(), List.of(), List.of());
    }

    private static UsersStatsResponse emptyUsers(final StatsWindow window) {
        return new UsersStatsResponse(window.days(), window.from(), window.to(), new StackedSeries(List.of(), List.of()),
                new UserInventory(0, 0, 0, 0, 0, 0, List.of()), 0, 0, new ApiKeyCounts(0, 0, 0), new McpCounts(0, 0));
    }

    private static SafetyStatsResponse emptySafety(final StatsWindow window) {
        return new SafetyStatsResponse(window.days(), window.from(), window.to(), new StackedSeries(List.of(), List.of()), 0, 0, List.of(),
                new ResolveTimes(0, null, null), new StackedSeries(List.of(), List.of()), new DomainRuleCounts(0, 0), 0, List.of());
    }

}
