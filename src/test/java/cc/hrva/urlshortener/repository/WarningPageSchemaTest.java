package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.Url;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = WarningPageSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:warning-page-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class WarningPageSchemaTest {

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void the035ChangesetRanBetween034And036() {
        assertThat(jdbcTemplate.queryForList("select id from databasechangelog where id like '034-%' or id like '035-%' or id like '036-%' "
                + "order by orderexecuted", String.class))
                .containsExactly("034-campaign", "034-campaign-link", "034-reported-domain", "035-url-warning-page", "036-user-sign-in-method",
                        "036-user-sign-in-method-backfill");
    }

    @Test
    void warningPageDefaultsToFalse() {
        jdbcTemplate.update("insert into url (id, short_url, long_url, active, visits) values (9001, 'plain', 'https://example.com/a', true, 0)");

        assertThat(jdbcTemplate.queryForObject("select warning_page from url where id = 9001", Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("select is_nullable from information_schema.columns where upper(table_name) = 'URL' "
                + "and upper(column_name) = 'WARNING_PAGE'", String.class)).isEqualTo("NO");
        assertThat(urlRepository.findById(9001L).orElseThrow().isWarningPage()).isFalse();
    }

    @Test
    void theFlagRoundTrips() {
        final var saved = urlRepository.saveAndFlush(Url.builder().shortUrl("flagged").longUrl("https://xdvqnxj.com/a").warningPage(true).build());
        entityManager.clear();

        assertThat(urlRepository.findById(saved.getId()).orElseThrow().isWarningPage()).isTrue();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = UrlRepository.class))
    static class TestApplication {
    }

}
