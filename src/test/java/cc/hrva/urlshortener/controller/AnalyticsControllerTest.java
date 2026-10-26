package cc.hrva.urlshortener.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.AnalyticsOverviewDto;
import cc.hrva.urlshortener.dto.AnalyticsOverviewDto.TopLink;
import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.LinkSummary;
import cc.hrva.urlshortener.exception.InvalidAnalyticsRangeException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.service.AnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    private static final String RANGE_ERROR = "days must be 7, 30, 90 or 365";

    private MockMvc mockMvc;

    @Mock
    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(analyticsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldReturnOverviewForTheRequestedRange() throws Exception {
        when(analyticsService.getOverview(90)).thenReturn(overview());

        mockMvc.perform(get("/api/v1/analytics/overview").param("days", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(90))
                .andExpect(jsonPath("$.from").value("2026-07-27"))
                .andExpect(jsonPath("$.to").value("2026-10-24"))
                .andExpect(jsonPath("$.totals.clicks").value(300))
                .andExpect(jsonPath("$.totals.uniqueVisitors").value(250))
                .andExpect(jsonPath("$.totals.botClicks").value(40))
                .andExpect(jsonPath("$.daily[0].date").value("2026-07-27"))
                .andExpect(jsonPath("$.daily[0].clicks").value(4))
                .andExpect(jsonPath("$.daily[0].uniqueVisitors").value(3))
                .andExpect(jsonPath("$.daily[0].botClicks").value(1))
                .andExpect(jsonPath("$.topLinks[0].id").value(7))
                .andExpect(jsonPath("$.topLinks[0].shortUrl").value("menu"))
                .andExpect(jsonPath("$.topLinks[0].destinationHost").value("example.com"))
                .andExpect(jsonPath("$.topLinks[0].clicks").value(120))
                .andExpect(jsonPath("$.topLinks[0].uniqueVisitors").value(95))
                .andExpect(jsonPath("$.topLinks[0].longUrl").doesNotExist())
                .andExpect(jsonPath("$.totalUrls").value(12))
                .andExpect(jsonPath("$.activeUrls").value(9))
                .andExpect(jsonPath("$.expiredUrls").value(2))
                .andExpect(jsonPath("$.totalVisits").value(4120));
    }

    @Test
    void overviewWithoutDaysLetsTheServicePickTheDefaultRange() throws Exception {
        when(analyticsService.getOverview(null)).thenReturn(overview());

        mockMvc.perform(get("/api/v1/analytics/overview"))
                .andExpect(status().isOk());

        verify(analyticsService).getOverview(null);
    }

    @Test
    void anUnsupportedOverviewRangeIsABadRequest() throws Exception {
        when(analyticsService.getOverview(14)).thenThrow(new InvalidAnalyticsRangeException(RANGE_ERROR));

        mockMvc.perform(get("/api/v1/analytics/overview").param("days", "14"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(RANGE_ERROR));
    }

    @Test
    void shouldReturnUrlAnalyticsForTheRequestedRange() throws Exception {
        when(analyticsService.getUrlAnalytics(7L, 7)).thenReturn(analytics());

        mockMvc.perform(get("/api/v1/analytics/urls/7").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.link.id").value(7))
                .andExpect(jsonPath("$.link.shortUrl").value("menu"))
                .andExpect(jsonPath("$.link.destinationHost").value("example.com"))
                .andExpect(jsonPath("$.link.status").value("ACTIVE"))
                .andExpect(jsonPath("$.link.passwordProtected").value(false))
                .andExpect(jsonPath("$.link.createDate").value("2026-10-01T12:00:00"))
                .andExpect(jsonPath("$.link.lastAccessed").value("2026-10-24T09:15:00"))
                .andExpect(jsonPath("$.link.visits").value(412))
                .andExpect(jsonPath("$.link.longUrl").value("https://example.com/menu?utm_source=flyer&utm_medium=print"))
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.from").value("2026-10-18"))
                .andExpect(jsonPath("$.to").value("2026-10-24"))
                .andExpect(jsonPath("$.totals.clicks").value(120))
                .andExpect(jsonPath("$.totals.uniqueVisitors").value(95))
                .andExpect(jsonPath("$.totals.botClicks").value(14))
                .andExpect(jsonPath("$.daily[0].date").value("2026-10-18"))
                .andExpect(jsonPath("$.daily[0].clicks").value(120))
                .andExpect(jsonPath("$.daily[0].uniqueVisitors").value(95))
                .andExpect(jsonPath("$.daily[0].botClicks").value(14))
                .andExpect(jsonPath("$.breakdowns.referrers[0].value").value("direct"))
                .andExpect(jsonPath("$.breakdowns.referrers[0].clicks").value(60))
                .andExpect(jsonPath("$.breakdowns.referrers[1].value").value("other"))
                .andExpect(jsonPath("$.breakdowns.countries[0].value").value("HR"))
                .andExpect(jsonPath("$.breakdowns.devices").isEmpty())
                .andExpect(jsonPath("$.breakdowns.browsers").isEmpty())
                .andExpect(jsonPath("$.breakdowns.operatingSystems").isEmpty())
                .andExpect(jsonPath("$.breakdowns.sources[0].value").value("direct"))
                .andExpect(jsonPath("$.breakdowns.sources[0].clicks").value(90))
                .andExpect(jsonPath("$.breakdowns.sources[1].value").value("qr"))
                .andExpect(jsonPath("$.breakdowns.sources[1].clicks").value(30));
    }

    @Test
    void anotherUsersLinkIsForbiddenWithoutItsDestination() throws Exception {
        when(analyticsService.getUrlAnalytics(7L, null)).thenThrow(new NoAuthorizationException("You don't have authorization for this action"));

        mockMvc.perform(get("/api/v1/analytics/urls/7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.link").doesNotExist());
    }

    @Test
    void withoutDaysTheServicePicksTheDefaultRange() throws Exception {
        when(analyticsService.getUrlAnalytics(7L, null)).thenReturn(analytics());

        mockMvc.perform(get("/api/v1/analytics/urls/7"))
                .andExpect(status().isOk());

        verify(analyticsService).getUrlAnalytics(7L, null);
    }

    @Test
    void anUnsupportedRangeIsABadRequest() throws Exception {
        when(analyticsService.getUrlAnalytics(7L, 14)).thenThrow(new InvalidAnalyticsRangeException(RANGE_ERROR));

        mockMvc.perform(get("/api/v1/analytics/urls/7").param("days", "14"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(RANGE_ERROR));
    }

    @Test
    void aRangeThatIsNotANumberIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/urls/7").param("days", "week"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(analyticsService);
    }

    private static AnalyticsOverviewDto overview() {
        return new AnalyticsOverviewDto(90, LocalDate.of(2026, 7, 27), LocalDate.of(2026, 10, 24), new AnalyticsTotals(300, 250, 40),
                List.of(new AnalyticsDay(LocalDate.of(2026, 7, 27), 4, 3, 1)),
                List.of(new TopLink(7L, "menu", "example.com", 120, 95)),
                12, 9, 2, 4120);
    }

    private static UrlAnalyticsDto analytics() {
        final var link = new LinkSummary(7L, "menu", "example.com", "ACTIVE", false,
                LocalDateTime.of(2026, 10, 1, 12, 0), LocalDateTime.of(2026, 10, 24, 9, 15), 412,
                "https://example.com/menu?utm_source=flyer&utm_medium=print");
        final var breakdowns = new Breakdowns(
                List.of(new BreakdownEntry("direct", 60), new BreakdownEntry("other", 3)),
                List.of(new BreakdownEntry("HR", 50)),
                List.of(),
                List.of(),
                List.of(),
                List.of(new BreakdownEntry("direct", 90), new BreakdownEntry("qr", 30)));
        return new UrlAnalyticsDto(link, 7, LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 24), new AnalyticsTotals(120, 95, 14),
                List.of(new AnalyticsDay(LocalDate.of(2026, 10, 18), 120, 95, 14)), breakdowns);
    }

}
