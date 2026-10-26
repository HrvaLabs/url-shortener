package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.dto.bio.BioViewDay;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = BioPageViewStatsRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:bio-page-view-stats;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class BioPageViewStatsRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    @Autowired
    private BioPageViewStatsRepository bioPageViewStatsRepository;

    @Autowired
    private BioPageRepository bioPageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void twoIncrementsOnADayAddUp() {
        final var page = savedPage("ana@example.com", "ana");

        bioPageViewStatsRepository.increment(page.getId(), DAY, 1, 0);
        bioPageViewStatsRepository.increment(page.getId(), DAY, 1, 0);
        bioPageViewStatsRepository.increment(page.getId(), DAY, 0, 1);

        assertThat(jdbcTemplate.queryForList("select stat_date, views, bot_views from bio_page_daily_views where page_id = ?", page.getId()))
                .extracting(row -> row.get("STAT_DATE").toString(), row -> row.get("VIEWS"), row -> row.get("BOT_VIEWS"))
                .containsExactly(tuple("2026-10-24", 2L, 1L));
    }

    @Test
    void daysAndPagesAreSeparate() {
        final var ana = savedPage("ana@example.com", "ana");
        final var bob = savedPage("bob@example.com", "bob");

        bioPageViewStatsRepository.increment(ana.getId(), DAY, 1, 0);
        bioPageViewStatsRepository.increment(ana.getId(), DAY.plusDays(1), 1, 0);
        bioPageViewStatsRepository.increment(bob.getId(), DAY, 0, 1);

        assertThat(jdbcTemplate.queryForList(
                "select page_id, stat_date, views, bot_views from bio_page_daily_views order by page_id, stat_date"))
                .extracting(row -> row.get("PAGE_ID"), row -> row.get("STAT_DATE").toString(), row -> row.get("VIEWS"), row -> row.get("BOT_VIEWS"))
                .containsExactly(
                        tuple(ana.getId(), "2026-10-24", 1L, 0L),
                        tuple(ana.getId(), "2026-10-25", 1L, 0L),
                        tuple(bob.getId(), "2026-10-24", 0L, 1L));
    }

    @Test
    void findDailyReturnsOnlyStoredDaysInTheRangeInOrder() {
        final var ana = savedPage("ana@example.com", "ana");
        final var bob = savedPage("bob@example.com", "bob");
        bioPageViewStatsRepository.increment(ana.getId(), DAY.plusDays(3), 4, 1);
        bioPageViewStatsRepository.increment(ana.getId(), DAY, 2, 0);
        bioPageViewStatsRepository.increment(ana.getId(), DAY.minusDays(1), 9, 9);
        bioPageViewStatsRepository.increment(ana.getId(), DAY.plusDays(7), 9, 9);
        bioPageViewStatsRepository.increment(bob.getId(), DAY.plusDays(1), 5, 5);

        assertThat(bioPageViewStatsRepository.findDaily(ana.getId(), DAY, DAY.plusDays(6))).containsExactly(
                new BioViewDay(DAY, 2, 0),
                new BioViewDay(DAY.plusDays(3), 4, 1));
        assertThat(bioPageViewStatsRepository.findDaily(bob.getId(), DAY.plusDays(2), DAY.plusDays(6))).isEmpty();
    }

    @Test
    void deletingThePageDeletesItsViews() {
        final var page = savedPage("ana@example.com", "ana");
        bioPageViewStatsRepository.increment(page.getId(), DAY, 1, 1);

        jdbcTemplate.update("delete from bio_page where id = ?", page.getId());

        assertThat(jdbcTemplate.queryForObject("select count(*) from bio_page_daily_views where page_id = ?", Long.class, page.getId()))
                .isZero();
    }

    private BioPage savedPage(final String email, final String handle) {
        final var owner = User.builder().email(email).password("correct horse battery").build();
        entityManager.persist(owner);
        return bioPageRepository.saveAndFlush(BioPage.builder().owner(owner).handle(handle).build());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BioPage.class)
    @EnableJpaRepositories(
            basePackageClasses = BioPageRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BioPageRepository.class))
    @Import(BioPageViewStatsRepository.class)
    static class TestApplication {
    }

}
