package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.model.enums.StatDimension;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class TrafficStatsRepository {

    private static final String FROM = "from";
    private static final String TO = "to";
    private static final String LIMIT = "limit";

    private static final String FIND_DAILY = """
            select stat_date, coalesce(sum(clicks), 0) as total_clicks, coalesce(sum(unique_visitors), 0) as total_unique_visitors,
                   coalesce(sum(bot_clicks), 0) as total_bot_clicks
            from link_daily_stats
            where stat_date between :from and :to
            group by stat_date
            order by stat_date
            """;

    private static final String FIND_CLICKS_BY_OWNERSHIP = """
            select coalesce(sum(case when u.owner_id is null then s.clicks else 0 end), 0) as anonymous_clicks,
                   coalesce(sum(case when u.owner_id is null then 0 else s.clicks end), 0) as account_clicks
            from link_daily_stats s join url u on u.id = s.url_id
            where s.stat_date between :from and :to
            """;

    private static final String FIND_TOP_LINKS = """
            select s.url_id, u.short_url, u.long_url, a.email as owner_email,
                   coalesce(sum(s.clicks), 0) as total_clicks, coalesce(sum(s.unique_visitors), 0) as total_unique_visitors
            from link_daily_stats s
            join url u on u.id = s.url_id
            left join user_account a on a.id = u.owner_id
            where s.stat_date between :from and :to
            group by s.url_id, u.short_url, u.long_url, a.email
            having sum(s.clicks) > 0
            order by sum(s.clicks) desc, s.url_id
            fetch first :limit rows only
            """;

    private static final String FIND_BREAKDOWN_TOTALS = """
            select dimension, dimension_value, coalesce(sum(clicks), 0) as total_clicks from link_daily_breakdown
            where stat_date between :from and :to
            group by dimension, dimension_value
            order by dimension, sum(clicks) desc, dimension_value
            """;

    private static final String SUM_ALL_TIME_VISITS = "select coalesce(sum(visits), 0) from url";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<AnalyticsDay> findDaily(final LocalDate from, final LocalDate to) {
        return jdbcTemplate.query(FIND_DAILY, Map.of(FROM, from, TO, to), (row, rowNumber) -> new AnalyticsDay(
                row.getObject("stat_date", LocalDate.class),
                row.getLong("total_clicks"),
                row.getLong("total_unique_visitors"),
                row.getLong("total_bot_clicks")));
    }

    public OwnershipClicks findClicksByOwnership(final LocalDate from, final LocalDate to) {
        return jdbcTemplate.queryForObject(FIND_CLICKS_BY_OWNERSHIP, Map.of(FROM, from, TO, to), (row, rowNumber) -> new OwnershipClicks(
                row.getLong("anonymous_clicks"),
                row.getLong("account_clicks")));
    }

    public List<TopLinkRow> findTopLinks(final LocalDate from, final LocalDate to, final int limit) {
        return jdbcTemplate.query(FIND_TOP_LINKS, Map.of(FROM, from, TO, to, LIMIT, limit), (row, rowNumber) -> new TopLinkRow(
                row.getLong("url_id"),
                row.getString("short_url"),
                row.getString("long_url"),
                row.getString("owner_email"),
                row.getLong("total_clicks"),
                row.getLong("total_unique_visitors")));
    }

    public Map<StatDimension, List<BreakdownEntry>> findBreakdownTotals(final LocalDate from, final LocalDate to) {
        final var totals = new EnumMap<StatDimension, List<BreakdownEntry>>(StatDimension.class);
        jdbcTemplate.query(FIND_BREAKDOWN_TOTALS, Map.of(FROM, from, TO, to), (RowCallbackHandler) row ->
                totals.computeIfAbsent(StatDimension.valueOf(row.getString("dimension")), dimension -> new ArrayList<>())
                        .add(new BreakdownEntry(row.getString("dimension_value"), row.getLong("total_clicks"))));
        return totals;
    }

    public long sumAllTimeVisits() {
        final var visits = jdbcTemplate.queryForObject(SUM_ALL_TIME_VISITS, Map.of(), Long.class);
        return visits != null ? visits : 0L;
    }

    public record OwnershipClicks(long anonymous, long account) {}

    public record TopLinkRow(long urlId, String shortUrl, String longUrl, String ownerEmail, long clicks, long uniqueVisitors) {}

}
