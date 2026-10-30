package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.model.enums.StatDimension;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AnonymousTrafficRepository {

    private static final String DAY = "day";
    private static final String MIN_CLICKS = "minClicks";
    private static final String LIMIT = "limit";
    private static final String URL_ID = "urlId";
    private static final String DIMENSION = "dimension";

    private static final String FIND_BUSY_ANONYMOUS_LINKS = """
            select s.url_id, u.short_url, u.long_url, s.clicks
            from link_daily_stats s join url u on u.id = s.url_id
            where s.stat_date = :day and u.owner_id is null and s.clicks >= :minClicks
            order by s.clicks desc, s.url_id
            fetch first :limit rows only
            """;

    private static final String SUM_DAY_CLICKS = """
            select coalesce(sum(s.clicks), 0) as total_clicks,
                   coalesce(sum(case when u.owner_id is null then s.clicks else 0 end), 0) as anonymous_clicks
            from link_daily_stats s join url u on u.id = s.url_id
            where s.stat_date = :day
            """;

    private static final String FIND_TOP_COUNTRY = """
            select dimension_value from link_daily_breakdown
            where url_id = :urlId and stat_date = :day and dimension = :dimension
              and dimension_value not in ('unknown', 'tor')
            order by clicks desc, dimension_value
            fetch first 1 rows only
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<BusyLink> busyAnonymousLinks(final LocalDate day, final long minClicks, final int limit) {
        return jdbcTemplate.query(FIND_BUSY_ANONYMOUS_LINKS, Map.of(DAY, day, MIN_CLICKS, minClicks, LIMIT, limit), (row, rowNumber) -> new BusyLink(
                row.getLong("url_id"),
                row.getString("short_url"),
                row.getString("long_url"),
                row.getLong("clicks")));
    }

    public DayClicks dayClicks(final LocalDate day) {
        return jdbcTemplate.queryForObject(SUM_DAY_CLICKS, Map.of(DAY, day), (row, rowNumber) -> new DayClicks(
                row.getLong("total_clicks"),
                row.getLong("anonymous_clicks")));
    }

    public Optional<String> topCountry(final long urlId, final LocalDate day) {
        return jdbcTemplate.queryForList(FIND_TOP_COUNTRY, Map.of(URL_ID, urlId, DAY, day, DIMENSION, StatDimension.COUNTRY.name()), String.class)
                .stream().findFirst();
    }

    public record BusyLink(long urlId, String shortUrl, String longUrl, long clicks) {}

    public record DayClicks(long total, long anonymous) {}

}
