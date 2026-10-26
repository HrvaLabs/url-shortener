package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse.BioPageViewTotals;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.StatDimension;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.stats.TrafficStatsRepository.OwnershipClicks;
import cc.hrva.urlshortener.repository.stats.TrafficStatsRepository.TopLinkRow;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

import static cc.hrva.urlshortener.model.enums.StatDimension.COUNTRY;
import static cc.hrva.urlshortener.model.enums.StatDimension.REFERRER;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = TrafficStatsRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:traffic-stats;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class TrafficStatsRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    @Autowired
    private TrafficStatsRepository trafficStatsRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void dailySumsEveryLinkPerDay() {
        final var first = savedLink("daily-a");
        final var second = savedLink("daily-b", savedOwner("daily@example.com"));
        dailyStats(first, DAY.plusDays(1), 1, 1, 0);
        dailyStats(first, DAY, 3, 2, 1);
        dailyStats(second, DAY, 5, 4, 0);
        dailyStats(second, DAY.minusDays(1), 9, 9, 9);
        dailyStats(first, DAY.plusDays(2), 7, 7, 7);

        assertThat(trafficStatsRepository.findDaily(DAY, DAY.plusDays(1))).containsExactly(
                new AnalyticsDay(DAY, 8, 6, 1),
                new AnalyticsDay(DAY.plusDays(1), 1, 1, 0));
        assertThat(trafficStatsRepository.findDaily(DAY.plusDays(3), DAY.plusDays(9))).isEmpty();
    }

    @Test
    void clicksSplitByOwnership() {
        final var anonymous = savedLink("split-anonymous");
        final var owned = savedLink("split-owned", savedOwner("split@example.com"));
        dailyStats(anonymous, DAY, 4, 3, 5);
        dailyStats(owned, DAY, 6, 5, 7);
        dailyStats(owned, DAY.minusDays(1), 9, 9, 0);
        dailyStats(anonymous, DAY.plusDays(1), 0, 0, 8);

        assertThat(trafficStatsRepository.findClicksByOwnership(DAY, DAY.plusDays(1))).isEqualTo(new OwnershipClicks(4, 6));
        assertThat(trafficStatsRepository.findClicksByOwnership(DAY.plusDays(2), DAY.plusDays(9))).isEqualTo(new OwnershipClicks(0, 0));
    }

    @Test
    void topLinksOrderByClicksWithTheOwnerEmail() {
        final var owner = savedOwner("ana@example.com");
        final var tiedFirst = savedLink("top-tied-1", owner);
        final var tiedSecond = savedLink("top-tied-2");
        final var busiest = savedLink("top-busiest", owner);
        final var botOnly = savedLink("top-bots", owner);
        dailyStats(tiedSecond, DAY, 4, 2, 0);
        dailyStats(tiedFirst, DAY, 1, 1, 2);
        dailyStats(tiedFirst, DAY.plusDays(1), 3, 2, 0);
        dailyStats(busiest, DAY, 6, 5, 0);
        dailyStats(busiest, DAY.plusDays(2), 50, 50, 0);
        dailyStats(botOnly, DAY, 0, 0, 3);

        assertThat(tiedFirst.getId()).isLessThan(tiedSecond.getId());
        assertThat(trafficStatsRepository.findTopLinks(DAY, DAY.plusDays(1), 10)).containsExactly(
                new TopLinkRow(busiest.getId(), "top-busiest", "https://example.org/top-busiest", "ana@example.com", 6, 5),
                new TopLinkRow(tiedFirst.getId(), "top-tied-1", "https://example.org/top-tied-1", "ana@example.com", 4, 3),
                new TopLinkRow(tiedSecond.getId(), "top-tied-2", "https://example.org/top-tied-2", null, 4, 2));
        assertThat(trafficStatsRepository.findTopLinks(DAY, DAY.plusDays(1), 1)).containsExactly(
                new TopLinkRow(busiest.getId(), "top-busiest", "https://example.org/top-busiest", "ana@example.com", 6, 5));
        assertThat(trafficStatsRepository.findTopLinks(DAY.plusDays(3), DAY.plusDays(9), 10)).isEmpty();
    }

    @Test
    void breakdownTotalsSumAcrossLinks() {
        final var first = savedLink("breakdown-a");
        final var second = savedLink("breakdown-b", savedOwner("breakdown@example.com"));
        breakdown(first, DAY, REFERRER, "direct", 2);
        breakdown(second, DAY.plusDays(1), REFERRER, "direct", 3);
        breakdown(second, DAY, REFERRER, "t.co", 4);
        breakdown(first, DAY, COUNTRY, "HR", 1);
        breakdown(second, DAY, COUNTRY, "AT", 1);
        breakdown(first, DAY.plusDays(2), REFERRER, "outside.example", 9);

        assertThat(trafficStatsRepository.findBreakdownTotals(DAY, DAY.plusDays(1))).isEqualTo(Map.of(
                REFERRER, List.of(new BreakdownEntry("direct", 5), new BreakdownEntry("t.co", 4)),
                COUNTRY, List.of(new BreakdownEntry("AT", 1), new BreakdownEntry("HR", 1))));
        assertThat(trafficStatsRepository.findBreakdownTotals(DAY.plusDays(3), DAY.plusDays(9))).isEmpty();
    }

    @Test
    void allTimeVisitsSumsUrlVisits() {
        assertThat(trafficStatsRepository.sumAllTimeVisits()).isZero();

        visits(savedLink("visits-3"), 3L);
        visits(savedLink("visits-4", savedOwner("visits@example.com")), 4L);
        visits(savedLink("visits-none"), null);

        assertThat(trafficStatsRepository.sumAllTimeVisits()).isEqualTo(7);
    }

    @Test
    void sumsBioPageViewsInTheWindowOnly() {
        final var anasPage = savedBioPage(savedOwner("bio-ana@example.com"), "ana");
        final var bensPage = savedBioPage(savedOwner("bio-ben@example.com"), "ben");
        bioPageViews(anasPage, DAY.minusDays(1), 50, 5);
        bioPageViews(anasPage, DAY, 7, 2);
        bioPageViews(bensPage, DAY, 3, 0);
        bioPageViews(bensPage, DAY.plusDays(1), 4, 1);
        bioPageViews(anasPage, DAY.plusDays(2), 90, 9);

        assertThat(trafficStatsRepository.sumBioPageViews(DAY, DAY.plusDays(1))).isEqualTo(new BioPageViewTotals(14, 3));
    }

    @Test
    void aQuietSiteSumsToZero() {
        assertThat(trafficStatsRepository.sumBioPageViews(DAY, DAY.plusDays(6))).isEqualTo(new BioPageViewTotals(0, 0));

        bioPageViews(savedBioPage(savedOwner("bio-quiet@example.com"), "quiet"), DAY.minusDays(1), 12, 4);

        assertThat(trafficStatsRepository.sumBioPageViews(DAY, DAY.plusDays(6))).isEqualTo(new BioPageViewTotals(0, 0));
    }

    private void dailyStats(final Url url, final LocalDate day, final long clicks, final long uniqueVisitors, final long botClicks) {
        jdbcTemplate.update("insert into link_daily_stats (url_id, stat_date, clicks, unique_visitors, bot_clicks) values (?, ?, ?, ?, ?)",
                url.getId(), day, clicks, uniqueVisitors, botClicks);
    }

    private void breakdown(final Url url, final LocalDate day, final StatDimension dimension, final String value, final long clicks) {
        jdbcTemplate.update("insert into link_daily_breakdown (url_id, stat_date, dimension, dimension_value, clicks) values (?, ?, ?, ?, ?)",
                url.getId(), day, dimension.name(), value, clicks);
    }

    private void visits(final Url url, final Long visits) {
        jdbcTemplate.update("update url set visits = ? where id = ?", visits, url.getId());
    }

    private Url savedLink(final String shortUrl) {
        return savedLink(shortUrl, null);
    }

    private Url savedLink(final String shortUrl, final User owner) {
        return urlRepository.saveAndFlush(Url.builder().shortUrl(shortUrl).longUrl("https://example.org/" + shortUrl).owner(owner).build());
    }

    private long savedBioPage(final User owner, final String handle) {
        final var now = LocalDateTime.of(2026, 10, 24, 9, 0);
        entityManager.flush();
        jdbcTemplate.update("insert into bio_page (user_id, handle, avatar_kind, accent, theme, created_at, updated_at) "
                + "values (?, ?, 'INITIALS', 'BLUE', 'AUTO', ?, ?)", owner.getId(), handle, now, now);
        return jdbcTemplate.queryForObject("select id from bio_page where handle = ?", Long.class, handle);
    }

    private void bioPageViews(final long pageId, final LocalDate day, final long views, final long botViews) {
        jdbcTemplate.update("insert into bio_page_daily_views (page_id, stat_date, views, bot_views) values (?, ?, ?, ?)",
                pageId, day, views, botViews);
    }

    private User savedOwner(final String email) {
        final var owner = User.builder().email(email).password("correct horse battery").build();
        entityManager.persist(owner);
        return owner;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UrlRepository.class))
    @Import(TrafficStatsRepository.class)
    static class TestApplication {
    }

}
