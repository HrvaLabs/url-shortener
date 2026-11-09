package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.client.AnalyticsEngineClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.stats.VisitStatsResponse;
import cc.hrva.urlshortener.exception.AnalyticsEngineException;
import cc.hrva.urlshortener.exception.FeatureUnavailableException;
import cc.hrva.urlshortener.model.enums.AnalyticsRange;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VisitStats {

    private static final Duration CACHE_FOR = Duration.ofHours(1);
    private static final int LONGEST_PERIOD_DAYS = 90;
    private static final int TOP = 20;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final String UNAVAILABLE = "Visit counts can't be read right now. Try again later.";
    private static final String SINCE = "WHERE timestamp > NOW() - INTERVAL '%d' DAY";
    private static final String SOURCES_SQL = "SELECT blob1 AS source, blob2 AS kind, SUM(_sample_interval) AS visits FROM %s " + SINCE
            + " GROUP BY source, kind ORDER BY visits DESC LIMIT " + TOP;
    private static final String PAGES_SQL = "SELECT blob3 AS page, SUM(_sample_interval) AS visits FROM %s " + SINCE
            + " GROUP BY page ORDER BY visits DESC LIMIT " + TOP;
    private static final String TOTAL_SQL = "SELECT SUM(_sample_interval) AS visits FROM %s " + SINCE;

    private final AnalyticsEngineClient client;
    private final StatsDays statsDays;
    private final Clock clock;
    private final AppProperties appProperties;
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

    public VisitStatsResponse collect(final AnalyticsRange requested) {
        final var effective = requested.days() > LONGEST_PERIOD_DAYS ? AnalyticsRange.LAST_90_DAYS : requested;
        final var window = statsDays.window(effective);
        if (!client.configured()) {
            return new VisitStatsResponse(false, window.days(), requested.days(), window.from(), window.to(), 0, List.of(), List.of());
        }
        final var now = clock.instant();
        final var cached = cache.get(window.days());
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.response().requestedAs(requested.days());
        }
        final var response = read(window, requested.days());
        cache.put(window.days(), new Cached(now.plus(CACHE_FOR), response));
        return response;
    }

    private VisitStatsResponse read(final StatsWindow window, final int requestedDays) {
        final var dataset = appProperties.getCloudflare().getLandingsDataset();
        if (dataset == null || !IDENTIFIER.matcher(dataset).matches()) {
            log.error("Visit counts skipped, the landings dataset name is not an identifier");
            throw new FeatureUnavailableException(UNAVAILABLE);
        }
        try {
            final var sources = client.query(SOURCES_SQL.formatted(dataset, window.days())).stream()
                    .map(row -> new VisitStatsResponse.Source(text(row.get("source")), text(row.get("kind")), count(row.get("visits"))))
                    .toList();
            final var pages = client.query(PAGES_SQL.formatted(dataset, window.days())).stream()
                    .map(row -> new VisitStatsResponse.Page(text(row.get("page")), count(row.get("visits"))))
                    .toList();
            final var totals = client.query(TOTAL_SQL.formatted(dataset, window.days()));
            final var total = totals.isEmpty() ? 0 : count(totals.getFirst().get("visits"));
            return new VisitStatsResponse(true, window.days(), requestedDays, window.from(), window.to(), total, sources, pages);
        } catch (final AnalyticsEngineException exception) {
            throw new FeatureUnavailableException(UNAVAILABLE);
        }
    }

    private static String text(final Object value) {
        return value == null ? "" : value.toString();
    }

    private static long count(final Object value) {
        if (value instanceof Number number) {
            return Math.round(number.doubleValue());
        }
        if (value == null || StringUtils.isBlank(value.toString())) {
            return 0;
        }
        try {
            return Math.round(Double.parseDouble(value.toString().strip()));
        } catch (final NumberFormatException exception) {
            throw new AnalyticsEngineException("Analytics Engine returned a count that is not a number");
        }
    }

    private record Cached(Instant expiresAt, VisitStatsResponse response) {
    }

}
