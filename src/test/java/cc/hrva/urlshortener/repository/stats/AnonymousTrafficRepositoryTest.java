package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.StatDimension;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.stats.AnonymousTrafficRepository.BusyLink;
import cc.hrva.urlshortener.repository.stats.AnonymousTrafficRepository.DayClicks;
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

import static cc.hrva.urlshortener.model.enums.StatDimension.COUNTRY;
import static cc.hrva.urlshortener.model.enums.StatDimension.REFERRER;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = AnonymousTrafficRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:anonymous-traffic;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AnonymousTrafficRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    @Autowired
    private AnonymousTrafficRepository anonymousTrafficRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void busyLinksReadTheZagrebDay() {
        final var busy = savedLink("day-busy");
        final var signedIn = savedLink("day-signed-in", savedOwner("day@example.com"));
        dailyStats(busy, DAY, 30, 0);
        dailyStats(busy, DAY.minusDays(1), 70, 0);
        dailyStats(busy, DAY.plusDays(1), 90, 0);
        dailyStats(signedIn, DAY, 500, 0);

        assertThat(anonymousTrafficRepository.busyAnonymousLinks(DAY, 25, 10))
                .containsExactly(new BusyLink(busy.getId(), "day-busy", "https://example.org/day-busy", 30));
        assertThat(anonymousTrafficRepository.busyAnonymousLinks(DAY, 31, 10)).isEmpty();
        assertThat(anonymousTrafficRepository.dayClicks(DAY)).isEqualTo(new DayClicks(530, 30));
        assertThat(anonymousTrafficRepository.dayClicks(DAY.plusDays(2))).isEqualTo(new DayClicks(0, 0));
    }

    @Test
    void botClicksDontCount() {
        final var anonymous = savedLink("bots-anonymous");
        final var signedIn = savedLink("bots-signed-in", savedOwner("bots@example.com"));
        dailyStats(anonymous, DAY, 3, 400);
        dailyStats(signedIn, DAY, 2, 600);

        assertThat(anonymousTrafficRepository.busyAnonymousLinks(DAY, 5, 10)).isEmpty();
        assertThat(anonymousTrafficRepository.busyAnonymousLinks(DAY, 1, 10)).extracting(BusyLink::clicks).containsExactly(3L);
        assertThat(anonymousTrafficRepository.dayClicks(DAY)).isEqualTo(new DayClicks(5, 3));
    }

    @Test
    void topCountryIsTheLargestBreakdown() {
        final var link = savedLink("country");
        final var other = savedLink("country-other");
        breakdown(link, DAY, COUNTRY, "HR", 4);
        breakdown(link, DAY, COUNTRY, "PT", 9);
        breakdown(link, DAY, COUNTRY, "AT", 9);
        breakdown(link, DAY, REFERRER, "direct", 50);
        breakdown(link, DAY.minusDays(1), COUNTRY, "DE", 80);
        breakdown(other, DAY, COUNTRY, "US", 70);

        assertThat(anonymousTrafficRepository.topCountry(link.getId(), DAY)).contains("AT");
        assertThat(anonymousTrafficRepository.topCountry(link.getId(), DAY.plusDays(1))).isEmpty();
    }

    @Test
    void busyLinksOrderByClicksAndStopAtTheLimit() {
        final var tiedFirst = savedLink("order-tied-1");
        final var tiedSecond = savedLink("order-tied-2");
        final var busiest = savedLink("order-busiest");
        final var quiet = savedLink("order-quiet");
        final var idle = savedLink("order-idle");
        dailyStats(tiedSecond, DAY, 40, 0);
        dailyStats(tiedFirst, DAY, 40, 0);
        dailyStats(busiest, DAY, 90, 0);
        dailyStats(quiet, DAY, 1, 0);
        dailyStats(idle, DAY, 0, 3);

        assertThat(tiedFirst.getId()).isLessThan(tiedSecond.getId());
        assertThat(anonymousTrafficRepository.busyAnonymousLinks(DAY, 1, 10)).extracting(BusyLink::shortUrl)
                .containsExactly("order-busiest", "order-tied-1", "order-tied-2", "order-quiet");
        assertThat(anonymousTrafficRepository.busyAnonymousLinks(DAY, 1, 2)).extracting(BusyLink::shortUrl)
                .containsExactly("order-busiest", "order-tied-1");
    }

    private void dailyStats(final Url url, final LocalDate day, final long clicks, final long botClicks) {
        jdbcTemplate.update("insert into link_daily_stats (url_id, stat_date, clicks, unique_visitors, bot_clicks) values (?, ?, ?, ?, ?)",
                url.getId(), day, clicks, clicks, botClicks);
    }

    private void breakdown(final Url url, final LocalDate day, final StatDimension dimension, final String value, final long clicks) {
        jdbcTemplate.update("insert into link_daily_breakdown (url_id, stat_date, dimension, dimension_value, clicks) values (?, ?, ?, ?, ?)",
                url.getId(), day, dimension.name(), value, clicks);
    }

    private Url savedLink(final String shortUrl) {
        return savedLink(shortUrl, null);
    }

    private Url savedLink(final String shortUrl, final User owner) {
        return urlRepository.saveAndFlush(Url.builder().shortUrl(shortUrl).longUrl("https://example.org/" + shortUrl).owner(owner).build());
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
    @Import(AnonymousTrafficRepository.class)
    static class TestApplication {
    }

}
