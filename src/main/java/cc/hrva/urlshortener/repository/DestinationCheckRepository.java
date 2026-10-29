package cc.hrva.urlshortener.repository;

import com.google.common.collect.Iterables;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DestinationCheckRepository {

    private static final String FIND_DUE = """
            select id, long_url, destination_fail_streak, destination_dead_since, owner_id, short_url
            from url
            where active = true and block_reason is null and threat_type is null
              and (destination_checked_at is null or destination_checked_at < :nightStart)
            %s
            order by destination_checked_at nulls first, id
            fetch first :limit rows only
            """;
    private static final int EXCLUDED_PER_LIST = 1000;

    private static final String RECORD = """
            update url set destination_checked_at = :checkedAt, destination_fail_streak = :failStreak,
                           destination_dead_since = :deadSince, destination_last_status = :lastStatus
            where id = :id and long_url = :checkedUrl
            """;

    private static final String TOUCH = """
            update url set destination_checked_at = :checkedAt where id = :id and long_url = :checkedUrl
            """;

    private static final String RESET = """
            update url set destination_checked_at = null, destination_fail_streak = 0,
                           destination_dead_since = null, destination_last_status = null
            where id = :id
            """;

    private static final String FIND_DIED_SINCE = """
            select id, owner_id, short_url, long_url, destination_dead_since, destination_last_status
            from url
            where owner_id is not null and destination_dead_since >= :since
            order by id
            """;

    private static final String COUNT_DEAD = "select count(*) from url where active = true and destination_dead_since is not null";
    private static final String ANONYMOUS = " and owner_id is null";
    private static final String OWNED = " and owner_id is not null";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<DueLink> findDue(final LocalDateTime nightStart, final int limit, final Collection<Long> excludedIds) {
        final var parameters = new MapSqlParameterSource().addValue("nightStart", nightStart).addValue("limit", limit);
        final var exclusions = new ArrayList<String>();
        for (final var chunk : Iterables.partition(List.copyOf(excludedIds), EXCLUDED_PER_LIST)) {
            final var name = "excluded" + exclusions.size();
            parameters.addValue(name, chunk);
            exclusions.add("and id not in (:" + name + ")");
        }
        return jdbcTemplate.query(FIND_DUE.formatted(String.join(" ", exclusions)), parameters, (row, rowNumber) -> new DueLink(
                row.getLong("id"),
                row.getString("long_url"),
                row.getInt("destination_fail_streak"),
                row.getObject("destination_dead_since", LocalDateTime.class),
                row.getObject("owner_id", Long.class),
                row.getString("short_url")));
    }

    public List<DiedLink> findDiedSince(final LocalDateTime since) {
        return jdbcTemplate.query(FIND_DIED_SINCE, Map.of("since", since), (row, rowNumber) -> new DiedLink(
                row.getLong("id"),
                row.getLong("owner_id"),
                row.getString("short_url"),
                row.getString("long_url"),
                row.getObject("destination_dead_since", LocalDateTime.class),
                row.getString("destination_last_status")));
    }

    public boolean record(final long id, final String checkedUrl, final LocalDateTime checkedAt, final int failStreak,
                          final LocalDateTime deadSince, final String lastStatus) {
        final var parameters = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("checkedUrl", checkedUrl)
                .addValue("checkedAt", checkedAt)
                .addValue("failStreak", failStreak)
                .addValue("deadSince", deadSince)
                .addValue("lastStatus", lastStatus);
        return jdbcTemplate.update(RECORD, parameters) == 1;
    }

    public boolean touch(final long id, final String checkedUrl, final LocalDateTime checkedAt) {
        return jdbcTemplate.update(TOUCH, Map.of("id", id, "checkedUrl", checkedUrl, "checkedAt", checkedAt)) == 1;
    }

    public void reset(final long id) {
        jdbcTemplate.update(RESET, Map.of("id", id));
    }

    public long countDead(final Boolean anonymous) {
        final var query = anonymous == null ? COUNT_DEAD : COUNT_DEAD + (anonymous ? ANONYMOUS : OWNED);
        final var count = jdbcTemplate.queryForObject(query, Map.of(), Long.class);
        return count != null ? count : 0L;
    }

    public record DueLink(long id, String longUrl, int failStreak, LocalDateTime deadSince, Long ownerId, String shortUrl) {
    }

    public record DiedLink(long id, long ownerId, String shortUrl, String longUrl, LocalDateTime deadSince, String lastStatus) {
    }

}
