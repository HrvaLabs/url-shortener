package cc.hrva.urlshortener.repository;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        classes = ShortUrlUniqueIndexGuardTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/short-url-duplicates-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:short-url-unique-index-guard;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.data.jpa.repositories.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ShortUrlUniqueIndexGuardTest {

    private static final String NOT_ENFORCED = "short_url uniqueness not enforced";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShortUrlUniqueIndex shortUrlUniqueIndex;

    @Test
    void theUniqueIndexIsSkippedWhenDuplicatesExist() {
        assertThat(jdbcTemplate.queryForList("select id, exectype from databasechangelog where id like '027-%' order by orderexecuted"))
                .extracting(row -> row.get("ID"), row -> row.get("EXECTYPE"))
                .containsExactly(
                        tuple("027-link-daily-stats-date-index", "EXECUTED"),
                        tuple("027-link-daily-breakdown-date-index", "EXECUTED"),
                        tuple("027-url-create-date-index", "EXECUTED"),
                        tuple("027-user-account-create-date-index", "EXECUTED"),
                        tuple("027-link-review-flagged-at-index", "EXECUTED"),
                        tuple("027-url-created-via", "EXECUTED"),
                        tuple("027-url-short-url-unique", "MARK_RAN"),
                        tuple("027-reserved-alias", "EXECUTED"),
                        tuple("027-url-long-url-length", "EXECUTED"));
    }

    @Test
    void theIndexStepIsMarkRanWhereDuplicatesExist() {
        assertThat(jdbcTemplate.queryForList("select id, exectype from databasechangelog where id like '032-url-%' order by orderexecuted"))
                .extracting(row -> row.get("ID"), row -> row.get("EXECTYPE"))
                .containsExactly(
                        tuple("032-url-domain-id", "EXECUTED"),
                        tuple("032-url-domain-short-url-unique", "MARK_RAN"),
                        tuple("032-url-short-url-unique-dropped", "MARK_RAN"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.indexes where upper(index_name) in ('UX_URL_SHORT_URL', 'UX_URL_DOMAIN_SHORT_URL')", Integer.class))
                .isZero();
    }

    @Test
    void bothDuplicatesSurvive() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from url where short_url = 'dup'", Integer.class)).isEqualTo(2);
    }

    @Test
    void notEnforcedAndSaysSo(final CapturedOutput output) {
        assertThat(shortUrlUniqueIndex.enforced()).isFalse();
        assertThat(output).containsOnlyOnce(NOT_ENFORCED);

        shortUrlUniqueIndex.warnIfNotEnforced();

        assertThat(StringUtils.countMatches(output, NOT_ENFORCED)).isEqualTo(2);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ShortUrlUniqueIndex.class)
    static class TestApplication {
    }

}
