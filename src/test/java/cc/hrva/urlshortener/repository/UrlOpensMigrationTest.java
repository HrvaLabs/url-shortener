package cc.hrva.urlshortener.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest(
        classes = UrlOpensMigrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/url-opens-migration-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:url-opens-migration;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.data.jpa.repositories.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class UrlOpensMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aLimitedLinkStartsWithItsVisitsAsOpensAndEveryOtherLinkAtZero() {
        assertThat(jdbcTemplate.query("select short_url, opens, visits from url where id <= 5 order by id",
                (row, index) -> tuple(row.getString("short_url"), row.getLong("opens"), row.getObject("visits"))))
                .containsExactly(
                        tuple("limited", 3L, 3L),
                        tuple("used-up", 5L, 5L),
                        tuple("unlimited", 0L, 40L),
                        tuple("no-limit", 0L, 7L),
                        tuple("never-visited", 0L, null));
    }

    @Test
    void aNewLinkStartsWithNoOpens() {
        jdbcTemplate.update("insert into url (id, long_url, short_url, visit_limit, active) values (10, 'https://example.com/new', 'new', 3, true)");

        assertThat(jdbcTemplate.queryForObject("select opens from url where id = 10", Long.class)).isZero();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

}
