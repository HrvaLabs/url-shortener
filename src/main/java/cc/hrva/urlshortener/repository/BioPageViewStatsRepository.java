package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.dto.bio.BioViewDay;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class BioPageViewStatsRepository {

    private static final String PAGE_ID = "pageId";
    private static final String DAY = "day";
    private static final String VIEWS = "views";
    private static final String BOT_VIEWS = "botViews";
    private static final String FROM = "from";
    private static final String TO = "to";

    private static final String INCREMENT = """
            MERGE INTO bio_page_daily_views t
            USING (VALUES (CAST(:pageId AS BIGINT), CAST(:day AS DATE), CAST(:views AS BIGINT), CAST(:botViews AS BIGINT)))
                  AS s(page_id, stat_date, views, bot_views)
            ON t.page_id = s.page_id AND t.stat_date = s.stat_date
            WHEN MATCHED THEN UPDATE SET views = t.views + s.views, bot_views = t.bot_views + s.bot_views
            WHEN NOT MATCHED THEN INSERT (page_id, stat_date, views, bot_views)
                 VALUES (s.page_id, s.stat_date, s.views, s.bot_views)
            """;

    private static final String FIND_DAILY = """
            select stat_date, views, bot_views from bio_page_daily_views
            where page_id = :pageId and stat_date between :from and :to
            order by stat_date
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public void increment(final long pageId, final LocalDate day, final long views, final long botViews) {
        final var parameters = Map.of(PAGE_ID, pageId, DAY, day, VIEWS, views, BOT_VIEWS, botViews);
        try {
            jdbcTemplate.update(INCREMENT, parameters);
        } catch (final DataIntegrityViolationException exception) {
            jdbcTemplate.update(INCREMENT, parameters);
        }
    }

    public List<BioViewDay> findDaily(final long pageId, final LocalDate from, final LocalDate to) {
        return jdbcTemplate.query(FIND_DAILY, Map.of(PAGE_ID, pageId, FROM, from, TO, to), (row, rowNumber) -> new BioViewDay(
                row.getObject("stat_date", LocalDate.class),
                row.getLong("views"),
                row.getLong("bot_views")));
    }

}
