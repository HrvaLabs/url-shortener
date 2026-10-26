package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.dto.LinkClicks;
import cc.hrva.urlshortener.model.enums.StatDimension;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    private static final String FROM = "from";
    private static final String TO = "to";
    private static final String OWNER_ID = "ownerId";
    private static final String LIMIT = "limit";
    private static final String URL_IDS = "urlIds";
    private static final String REFERRER = "referrer";

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

    private static final String FIND_DAILY = """
            select stat_date, clicks, unique_visitors, bot_clicks from link_daily_stats
            where url_id = :urlId and stat_date between :from and :to
            order by stat_date
            """;

    private static final String FIND_BREAKDOWN_TOTALS = """
            select dimension, dimension_value, sum(clicks) as total_clicks from link_daily_breakdown
            where url_id = :urlId and stat_date between :from and :to
            group by dimension, dimension_value
            order by dimension, sum(clicks) desc, dimension_value
            """;

    private static final String FIND_OWNER_DAILY = """
            select s.stat_date, sum(s.clicks) as total_clicks, sum(s.unique_visitors) as total_unique_visitors,
                   sum(s.bot_clicks) as total_bot_clicks
            from link_daily_stats s join url u on u.id = s.url_id
            where u.owner_id = :ownerId and s.stat_date between :from and :to
            group by s.stat_date
            order by s.stat_date
            """;

    private static final String FIND_OWNER_TOP_LINKS = """
            select s.url_id, sum(s.clicks) as total_clicks, sum(s.unique_visitors) as total_unique_visitors
            from link_daily_stats s join url u on u.id = s.url_id
            where u.owner_id = :ownerId and s.stat_date between :from and :to
            group by s.url_id
            having sum(s.clicks) > 0
            order by sum(s.clicks) desc, s.url_id
            fetch first :limit rows only
            """;

    private static final String SUM_CLICKS_BY_URL = """
            select url_id, sum(clicks) as total_clicks from link_daily_stats
            where url_id in (:urlIds) and stat_date between :from and :to
            group by url_id
            """;

    private static final String SUM_REFERRER_CLICKS_BY_URL = """
            select url_id, sum(clicks) as total_clicks from link_daily_breakdown
            where url_id in (:urlIds) and dimension = 'REFERRER' and dimension_value = :referrer and stat_date between :from and :to
            group by url_id
            """;

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

    public List<AnalyticsDay> findDaily(final long urlId, final LocalDate from, final LocalDate to) {
        return jdbcTemplate.query(FIND_DAILY, Map.of(URL_ID, urlId, FROM, from, TO, to), (row, rowNumber) -> new AnalyticsDay(
                row.getObject("stat_date", LocalDate.class),
                row.getLong("clicks"),
                row.getLong("unique_visitors"),
                row.getLong("bot_clicks")));
    }

    public Map<StatDimension, List<BreakdownEntry>> findBreakdownTotals(final long urlId, final LocalDate from, final LocalDate to) {
        final var totals = new EnumMap<StatDimension, List<BreakdownEntry>>(StatDimension.class);
        jdbcTemplate.query(FIND_BREAKDOWN_TOTALS, Map.of(URL_ID, urlId, FROM, from, TO, to), (RowCallbackHandler) row ->
                totals.computeIfAbsent(StatDimension.valueOf(row.getString("dimension")), dimension -> new ArrayList<>())
                        .add(new BreakdownEntry(row.getString("dimension_value"), row.getLong("total_clicks"))));
        return totals;
    }

    public List<AnalyticsDay> findOwnerDaily(final long ownerId, final LocalDate from, final LocalDate to) {
        return jdbcTemplate.query(FIND_OWNER_DAILY, Map.of(OWNER_ID, ownerId, FROM, from, TO, to), (row, rowNumber) -> new AnalyticsDay(
                row.getObject("stat_date", LocalDate.class),
                row.getLong("total_clicks"),
                row.getLong("total_unique_visitors"),
                row.getLong("total_bot_clicks")));
    }

    public List<LinkClicks> findOwnerTopLinks(final long ownerId, final LocalDate from, final LocalDate to, final int limit) {
        return jdbcTemplate.query(FIND_OWNER_TOP_LINKS, Map.of(OWNER_ID, ownerId, FROM, from, TO, to, LIMIT, limit),
                (row, rowNumber) -> new LinkClicks(
                        row.getLong("url_id"),
                        row.getLong("total_clicks"),
                        row.getLong("total_unique_visitors")));
    }

    public Map<Long, Long> sumClicksByUrl(final Collection<Long> urlIds, final LocalDate from, final LocalDate to) {
        if (urlIds.isEmpty()) {
            return Map.of();
        }
        return sumsByUrl(SUM_CLICKS_BY_URL, Map.of(URL_IDS, urlIds, FROM, from, TO, to));
    }

    public Map<Long, Long> sumReferrerClicksByUrl(final Collection<Long> urlIds, final String referrer, final LocalDate from, final LocalDate to) {
        if (urlIds.isEmpty()) {
            return Map.of();
        }
        return sumsByUrl(SUM_REFERRER_CLICKS_BY_URL, Map.of(URL_IDS, urlIds, REFERRER, referrer, FROM, from, TO, to));
    }

    private Map<Long, Long> sumsByUrl(final String sql, final Map<String, ?> parameters) {
        final var sums = new HashMap<Long, Long>();
        jdbcTemplate.query(sql, parameters, (RowCallbackHandler) row -> sums.put(row.getLong("url_id"), row.getLong("total_clicks")));
        return Map.copyOf(sums);
    }

    private void updateRetryingOnce(final String sql, final Map<String, ?> parameters) {
        try {
            jdbcTemplate.update(sql, parameters);
        } catch (final DataIntegrityViolationException exception) {
            jdbcTemplate.update(sql, parameters);
        }
    }

}
