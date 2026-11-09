package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.client.AnalyticsEngineClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.stats.VisitStatsResponse;
import cc.hrva.urlshortener.exception.AnalyticsEngineException;
import cc.hrva.urlshortener.exception.FeatureUnavailableException;
import cc.hrva.urlshortener.model.enums.AnalyticsRange;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.notification.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisitStatsTest {

    private static final String UNAVAILABLE = "Visit counts can't be read right now. Try again later.";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    private final AppProperties appProperties = new AppProperties();
    private final AnalyticsEngineClient client = mock(AnalyticsEngineClient.class);

    private VisitStats visitStats;

    @BeforeEach
    void setUp() {
        appProperties.getAnalytics().setZone(ZoneId.of("Europe/Zagreb"));
        final var statsDays = new StatsDays(new AnalyticsDays(clock, appProperties), clock, ZoneOffset.UTC);
        visitStats = new VisitStats(client, statsDays, clock, appProperties);
        when(client.configured()).thenReturn(true);
        when(client.query(anyString())).thenReturn(List.of());
    }

    @Test
    void notConfiguredAnswersNotConnectedWithoutCalling() {
        when(client.configured()).thenReturn(false);

        final var response = visitStats.collect(AnalyticsRange.LAST_30_DAYS);

        assertThat(response).isEqualTo(new VisitStatsResponse(false, 30, 30, LocalDate.of(2026, 10, 11), LocalDate.of(2026, 11, 9), 0, List.of(), List.of()));
        verify(client, never()).query(anyString());
    }

    @Test
    void a365DayRequestIsAnsweredFor90() {
        final var response = visitStats.collect(AnalyticsRange.LAST_365_DAYS);

        assertThat(response.days()).isEqualTo(90);
        assertThat(response.requestedDays()).isEqualTo(365);
        assertThat(response.from()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(response.to()).isEqualTo(LocalDate.of(2026, 11, 9));
        final var queries = ArgumentCaptor.forClass(String.class);
        verify(client, times(3)).query(queries.capture());
        assertThat(queries.getAllValues()).allSatisfy(sql -> assertThat(sql).contains("INTERVAL '90' DAY"));
    }

    @Test
    void rowsAreMappedInOrder() {
        when(client.query(anyString())).thenAnswer(invocation -> {
            final String sql = invocation.getArgument(0);
            if (sql.contains("GROUP BY source")) {
                return List.of(Map.<String, Object>of("source", "chatgpt.com", "kind", "utm", "visits", 13),
                        Map.<String, Object>of("source", "direct", "kind", "direct", "visits", "7"));
            }
            if (sql.contains("GROUP BY page")) {
                return List.of(Map.<String, Object>of("page", "/", "visits", 300L), Map.<String, Object>of("page", "/blog/x", "visits", 4.0));
            }
            return List.of(Map.<String, Object>of("visits", "480"));
        });

        final var response = visitStats.collect(AnalyticsRange.LAST_30_DAYS);

        assertThat(response.connected()).isTrue();
        assertThat(response.total()).isEqualTo(480);
        assertThat(response.sources()).containsExactly(new VisitStatsResponse.Source("chatgpt.com", "utm", 13), new VisitStatsResponse.Source("direct", "direct", 7));
        assertThat(response.pages()).containsExactly(new VisitStatsResponse.Page("/", 300), new VisitStatsResponse.Page("/blog/x", 4));
    }

    @Test
    void theQueriesUseTheConfiguredDatasetAndTheValidatedDays() {
        appProperties.getCloudflare().setLandingsDataset("my_landings2");

        visitStats.collect(AnalyticsRange.LAST_7_DAYS);

        final var queries = ArgumentCaptor.forClass(String.class);
        verify(client, times(3)).query(queries.capture());
        assertThat(queries.getAllValues()).containsExactly(
                "SELECT blob1 AS source, blob2 AS kind, SUM(_sample_interval) AS visits FROM my_landings2 WHERE timestamp > NOW() - INTERVAL '7' DAY GROUP BY source, kind ORDER BY visits DESC LIMIT 20",
                "SELECT blob3 AS page, SUM(_sample_interval) AS visits FROM my_landings2 WHERE timestamp > NOW() - INTERVAL '7' DAY GROUP BY page ORDER BY visits DESC LIMIT 20",
                "SELECT SUM(_sample_interval) AS visits FROM my_landings2 WHERE timestamp > NOW() - INTERVAL '7' DAY");
    }

    @Test
    void aDatasetThatIsNotAnIdentifierIsNeverPutIntoSql() {
        appProperties.getCloudflare().setLandingsDataset("hrva_landings; DROP TABLE x");

        assertThatThrownBy(() -> visitStats.collect(AnalyticsRange.LAST_30_DAYS))
                .isInstanceOf(FeatureUnavailableException.class)
                .hasMessage(UNAVAILABLE);
        verify(client, never()).query(anyString());
    }

    @Test
    void aSecondCallWithinAnHourIsCached() {
        visitStats.collect(AnalyticsRange.LAST_30_DAYS);
        clock.advance(Duration.ofMinutes(59));
        visitStats.collect(AnalyticsRange.LAST_30_DAYS);

        verify(client, times(3)).query(anyString());
    }

    @Test
    void the90And365DayRequestsShareOneCacheEntry() {
        visitStats.collect(AnalyticsRange.LAST_90_DAYS);
        final var again = visitStats.collect(AnalyticsRange.LAST_365_DAYS);

        verify(client, times(3)).query(anyString());
        assertThat(again.requestedDays()).isEqualTo(365);
        assertThat(again.days()).isEqualTo(90);
    }

    @Test
    void aCallAfterAnHourQueriesAgain() {
        visitStats.collect(AnalyticsRange.LAST_30_DAYS);
        clock.advance(Duration.ofMinutes(61));
        visitStats.collect(AnalyticsRange.LAST_30_DAYS);

        verify(client, times(6)).query(anyString());
    }

    @Test
    void aFailureIsNotCached() {
        when(client.query(anyString())).thenThrow(new AnalyticsEngineException("Analytics Engine answered 500"));

        assertThatThrownBy(() -> visitStats.collect(AnalyticsRange.LAST_30_DAYS))
                .isInstanceOf(FeatureUnavailableException.class)
                .hasMessage(UNAVAILABLE);

        doReturn(List.of()).when(client).query(anyString());
        final var response = visitStats.collect(AnalyticsRange.LAST_30_DAYS);

        assertThat(response.connected()).isTrue();
    }

    @Test
    void notConnectedIsNeverCachedEither() {
        when(client.configured()).thenReturn(false);
        visitStats.collect(AnalyticsRange.LAST_30_DAYS);
        when(client.configured()).thenReturn(true);

        assertThat(visitStats.collect(AnalyticsRange.LAST_30_DAYS).connected()).isTrue();
        verify(client, times(3)).query(anyString());
    }

}
