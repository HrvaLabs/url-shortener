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
        classes = ReviewWarningPageSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:review-warning-page-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ReviewWarningPageSchemaTest {

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void the047ChangesetRanAfter046() {
        assertThat(jdbcTemplate.queryForList("select id from databasechangelog where id like '046-%' or id like '047-%' order by orderexecuted",
                String.class))
                .containsExactly("046-domain-removed-by-admin", "046-domain-removed-by-admin-backfill", "046-domain-removed-by-admin-audited",
                        "047-url-warning-page-for-review");
    }

    @Test
    void theReasonDefaultsToFalse() {
        jdbcTemplate.update("insert into url (id, short_url, long_url, active, visits, warning_page) values (9101, 'young', 'https://xdvqnxj.com/a', true, 0, true)");

        assertThat(jdbcTemplate.queryForObject("select warning_page_for_review from url where id = 9101", Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("select is_nullable from information_schema.columns where upper(table_name) = 'URL' "
                + "and upper(column_name) = 'WARNING_PAGE_FOR_REVIEW'", String.class)).isEqualTo("NO");
        assertThat(urlRepository.findById(9101L).orElseThrow()).extracting(Url::isWarningPage, Url::isWarningPageForReview).containsExactly(true, false);
    }

    @Test
    void theReasonRoundTrips() {
        final var saved = urlRepository.saveAndFlush(Url.builder().shortUrl("inreview").longUrl("https://utn.pl/a").warningPage(true)
                .warningPageForReview(true).build());
        entityManager.clear();

        assertThat(urlRepository.findById(saved.getId()).orElseThrow()).extracting(Url::isWarningPage, Url::isWarningPageForReview)
                .containsExactly(true, true);
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
