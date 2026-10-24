package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.StatDimension;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static cc.hrva.urlshortener.model.enums.StatDimension.BROWSER;
import static cc.hrva.urlshortener.model.enums.StatDimension.COUNTRY;
import static cc.hrva.urlshortener.model.enums.StatDimension.DEVICE;
import static cc.hrva.urlshortener.model.enums.StatDimension.OS;
import static cc.hrva.urlshortener.model.enums.StatDimension.REFERRER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = LinkStatsRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:link-stats;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class LinkStatsRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);
    private static final Map<StatDimension, String> CROATIAN_IPHONE =
            Map.of(REFERRER, "direct", COUNTRY, "HR", DEVICE, "mobile", BROWSER, "safari", OS, "ios");
    private static final Map<StatDimension, String> GERMAN_IPHONE =
            Map.of(REFERRER, "direct", COUNTRY, "DE", DEVICE, "mobile", BROWSER, "safari", OS, "ios");

    @Autowired
    private LinkStatsRepository linkStatsRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void incrementsTheDayRowInPlace() {
        final var url = savedLink("daily");

        linkStatsRepository.incrementDailyStats(url.getId(), DAY, 1, 1, 0);
        linkStatsRepository.incrementDailyStats(url.getId(), DAY, 1, 1, 0);
        linkStatsRepository.incrementDailyStats(url.getId(), DAY, 0, 0, 1);
        linkStatsRepository.incrementDailyStats(url.getId(), DAY.plusDays(1), 1, 0, 0);

        assertThat(jdbcTemplate.queryForList(
                "select stat_date, clicks, unique_visitors, bot_clicks from link_daily_stats where url_id = ? order by stat_date", url.getId()))
                .extracting(row -> row.get("STAT_DATE").toString(), row -> row.get("CLICKS"), row -> row.get("UNIQUE_VISITORS"),
                        row -> row.get("BOT_CLICKS"))
                .containsExactly(
                        tuple("2026-10-24", 2L, 2L, 1L),
                        tuple("2026-10-25", 1L, 0L, 0L));
    }

    @Test
    void breakdownInsertsNewValuesAndIncrementsExistingOnesInOneCall() {
        final var url = savedLink("breakdown");

        recordBreakdowns(url);

        assertThat(jdbcTemplate.queryForList(
                "select dimension, dimension_value, clicks from link_daily_breakdown where url_id = ? and stat_date = ?", url.getId(), DAY))
                .extracting(row -> row.get("DIMENSION"), row -> row.get("DIMENSION_VALUE"), row -> row.get("CLICKS"))
                .containsExactlyInAnyOrder(
                        tuple("REFERRER", "direct", 3L),
                        tuple("COUNTRY", "HR", 2L),
                        tuple("COUNTRY", "DE", 1L),
                        tuple("DEVICE", "mobile", 3L),
                        tuple("BROWSER", "safari", 3L),
                        tuple("OS", "ios", 3L));
    }

    @Test
    void findsTheValuesStoredForADay() {
        final var url = savedLink("values");

        recordBreakdowns(url);

        assertThat(linkStatsRepository.findBreakdownValues(url.getId(), DAY)).isEqualTo(Map.of(
                REFERRER, Set.of("direct"),
                COUNTRY, Set.of("HR", "DE"),
                DEVICE, Set.of("mobile"),
                BROWSER, Set.of("safari"),
                OS, Set.of("ios")));
        assertThat(linkStatsRepository.findBreakdownValues(url.getId(), DAY.plusDays(1))).isEmpty();
    }

    @Test
    void keepsAValueOf255Characters() {
        final var url = savedLink("long");
        final var host = "a".repeat(251) + ".com";

        assertThat(host).hasSize(255);

        linkStatsRepository.incrementBreakdown(url.getId(), DAY, Map.of(REFERRER, host));

        assertThat(linkStatsRepository.findBreakdownValues(url.getId(), DAY)).isEqualTo(Map.of(REFERRER, Set.of(host)));
    }

    @Test
    void anEmptyBreakdownWritesNothing() {
        final var url = savedLink("empty");

        linkStatsRepository.incrementBreakdown(url.getId(), DAY, Map.of());

        assertThat(linkStatsRepository.findBreakdownValues(url.getId(), DAY)).isEmpty();
    }

    @Test
    void deletingTheLinkDeletesItsStats() {
        final var url = savedLink("deleted");
        linkStatsRepository.incrementDailyStats(url.getId(), DAY, 1, 1, 0);
        linkStatsRepository.incrementBreakdown(url.getId(), DAY, CROATIAN_IPHONE);

        urlRepository.delete(url);
        urlRepository.flush();

        assertThat(countRows("link_daily_stats", url.getId())).isZero();
        assertThat(countRows("link_daily_breakdown", url.getId())).isZero();
    }

    private void recordBreakdowns(final Url url) {
        linkStatsRepository.incrementBreakdown(url.getId(), DAY, CROATIAN_IPHONE);
        linkStatsRepository.incrementBreakdown(url.getId(), DAY, CROATIAN_IPHONE);
        linkStatsRepository.incrementBreakdown(url.getId(), DAY, GERMAN_IPHONE);
    }

    private Integer countRows(final String table, final long urlId) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where url_id = ?", Integer.class, urlId);
    }

    private Url savedLink(final String shortUrl) {
        return urlRepository.saveAndFlush(Url.builder().shortUrl(shortUrl).longUrl("https://example.org/" + shortUrl).build());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UrlRepository.class))
    @Import(LinkStatsRepository.class)
    static class TestApplication {
    }

}
