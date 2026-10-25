package cc.hrva.urlshortener.repository.stats;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class HourlyCountRepository {

    private static final String START = "start";
    private static final String END = "end";

    private static final String COUNT_PER_HOUR = """
            select cast(%2$s as date) as bucket_date, extract(hour from %2$s) as bucket_hour, %3$s as bucket_key, count(*) as bucket_count
            from %1$s
            where %2$s >= :start and %2$s < :end
            group by cast(%2$s as date), extract(hour from %2$s), %3$s
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<HourlyCount> count(final HourlySeries series, final LocalDateTime start, final LocalDateTime end) {
        final var sql = COUNT_PER_HOUR.formatted(series.getTable(), series.getTimestampColumn(), series.getKeyExpression());
        return jdbcTemplate.query(sql, Map.of(START, start, END, end), (row, rowNumber) -> new HourlyCount(
                row.getObject("bucket_date", LocalDate.class),
                row.getInt("bucket_hour"),
                row.getString("bucket_key"),
                row.getLong("bucket_count")));
    }

}
