package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.exception.InvalidAnalyticsRangeException;
import cc.hrva.urlshortener.service.impl.DefaultAdminStatsService;
import cc.hrva.urlshortener.service.stats.StatsDays;
import cc.hrva.urlshortener.service.stats.StatsWindow;
import cc.hrva.urlshortener.service.stats.TrafficStats;
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

    private AdminStatsService adminStatsService;

    @BeforeEach
    void setUp() {
        this.adminStatsService = new DefaultAdminStatsService(statsDays, trafficStats);
    }

    @Test
    void rejectsOtherRangesBeforeReadingAnything() {
        assertThatThrownBy(() -> adminStatsService.traffic(14))
                .isInstanceOf(InvalidAnalyticsRangeException.class)
                .hasMessage(RANGE_ERROR);

        verifyNoInteractions(statsDays, trafficStats);
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

    private static TrafficStatsResponse emptyTraffic(final StatsWindow window) {
        return new TrafficStatsResponse(window.days(), window.from(), window.to(), new AnalyticsTotals(0, 0, 0), List.of(), 0, 0,
                List.of(), new Breakdowns(List.of(), List.of(), List.of(), List.of(), List.of()), 0);
    }

}
