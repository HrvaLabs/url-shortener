package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.dto.stats.CountEntry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SafetyStatsRepository {

    private static final String START = "start";
    private static final String END = "end";

    private static final String COUNT_RESOLVED_BY_RESOLUTION = """
            select coalesce(resolution, 'unknown') as review_resolution, count(*) as total_reviews from link_review
            where resolved_at >= :start and resolved_at < :end
            group by coalesce(resolution, 'unknown')
            order by count(*) desc, coalesce(resolution, 'unknown')
            """;

    private static final String FIND_RESOLVED_TIMES = """
            select flagged_at, resolved_at from link_review
            where resolved_at >= :start and resolved_at < :end
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<CountEntry> countResolvedByResolution(final LocalDateTime start, final LocalDateTime end) {
        return jdbcTemplate.query(COUNT_RESOLVED_BY_RESOLUTION, Map.of(START, start, END, end), (row, rowNumber) ->
                new CountEntry(row.getString("review_resolution"), row.getLong("total_reviews")));
    }

    public List<Long> findResolveMinutes(final LocalDateTime start, final LocalDateTime end) {
        return jdbcTemplate.query(FIND_RESOLVED_TIMES, Map.of(START, start, END, end), (row, rowNumber) ->
                        Math.max(0, Duration.between(row.getObject("flagged_at", LocalDateTime.class),
                                row.getObject("resolved_at", LocalDateTime.class)).toMinutes()))
                .stream()
                .sorted()
                .toList();
    }

}
