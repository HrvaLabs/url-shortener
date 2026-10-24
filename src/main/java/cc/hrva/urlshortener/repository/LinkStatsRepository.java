package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.enums.StatDimension;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LinkStatsRepository {

    private static final String URL_ID = "urlId";
    private static final String DAY = "day";
    private static final String DIMENSION = "dimension";
    private static final String VALUE = "value";

    private static final String INCREMENT_DAILY_STATS = """
            MERGE INTO link_daily_stats t
            USING (VALUES (CAST(:urlId AS BIGINT), CAST(:day AS DATE), CAST(:clicks AS BIGINT),
                           CAST(:uniqueVisitors AS BIGINT), CAST(:botClicks AS BIGINT)))
                  AS s(url_id, stat_date, clicks, unique_visitors, bot_clicks)
            ON t.url_id = s.url_id AND t.stat_date = s.stat_date
            WHEN MATCHED THEN UPDATE SET clicks = t.clicks + s.clicks,
                 unique_visitors = t.unique_visitors + s.unique_visitors, bot_clicks = t.bot_clicks + s.bot_clicks
            WHEN NOT MATCHED THEN INSERT (url_id, stat_date, clicks, unique_visitors, bot_clicks)
                 VALUES (s.url_id, s.stat_date, s.clicks, s.unique_visitors, s.bot_clicks)
            """;

    private static final String INCREMENT_BREAKDOWN = """
            MERGE INTO link_daily_breakdown t
            USING (VALUES %s)
                  AS s(url_id, stat_date, dimension, dimension_value)
            ON t.url_id = s.url_id AND t.stat_date = s.stat_date AND t.dimension = s.dimension AND t.dimension_value = s.dimension_value
            WHEN MATCHED THEN UPDATE SET clicks = t.clicks + 1
            WHEN NOT MATCHED THEN INSERT (url_id, stat_date, dimension, dimension_value, clicks)
                 VALUES (s.url_id, s.stat_date, s.dimension, s.dimension_value, 1)
            """;

    private static final String BREAKDOWN_ROW =
            "(CAST(:urlId AS BIGINT), CAST(:day AS DATE), CAST(:dimension%1$d AS VARCHAR(10)), CAST(:value%1$d AS VARCHAR(255)))";

    private static final String FIND_BREAKDOWN_VALUES =
            "select dimension, dimension_value from link_daily_breakdown where url_id = :urlId and stat_date = :day";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public void incrementDailyStats(final long urlId, final LocalDate day, final long clicks, final long uniqueVisitors, final long botClicks) {
        updateRetryingOnce(INCREMENT_DAILY_STATS, Map.of(
                URL_ID, urlId,
                DAY, day,
                "clicks", clicks,
                "uniqueVisitors", uniqueVisitors,
                "botClicks", botClicks));
    }

    public void incrementBreakdown(final long urlId, final LocalDate day, final Map<StatDimension, String> values) {
        if (values.isEmpty()) {
            return;
        }
        final var parameters = new HashMap<String, Object>();
        parameters.put(URL_ID, urlId);
        parameters.put(DAY, day);
        final var entries = values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        final var rows = new StringJoiner(", ");
        for (var index = 0; index < entries.size(); index++) {
            parameters.put(DIMENSION + index, entries.get(index).getKey().name());
            parameters.put(VALUE + index, entries.get(index).getValue());
            rows.add(BREAKDOWN_ROW.formatted(index));
        }
        updateRetryingOnce(INCREMENT_BREAKDOWN.formatted(rows), parameters);
    }

    public Map<StatDimension, Set<String>> findBreakdownValues(final long urlId, final LocalDate day) {
        final var values = new EnumMap<StatDimension, Set<String>>(StatDimension.class);
        jdbcTemplate.query(FIND_BREAKDOWN_VALUES, Map.of(URL_ID, urlId, DAY, day), (RowCallbackHandler) row ->
                values.computeIfAbsent(StatDimension.valueOf(row.getString("dimension")), dimension -> new HashSet<>())
                        .add(row.getString("dimension_value")));
        return values;
    }

    private void updateRetryingOnce(final String sql, final Map<String, ?> parameters) {
        try {
            jdbcTemplate.update(sql, parameters);
        } catch (final DataIntegrityViolationException exception) {
            jdbcTemplate.update(sql, parameters);
        }
    }

}
