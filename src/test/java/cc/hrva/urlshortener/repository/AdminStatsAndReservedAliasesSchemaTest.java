package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.Url;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = AdminStatsAndReservedAliasesSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:admin-stats-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AdminStatsAndReservedAliasesSchemaTest {

    private static final String INSERT_RESERVED_ALIAS =
            "insert into reserved_alias (alias, reason, created_by, created_at) values (?, ?, ?, CURRENT_TIMESTAMP)";
    private static final String ADMIN = "admin@hrva.cc";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShortUrlUniqueIndex shortUrlUniqueIndex;

    @Test
    void everyChangesetOf027Ran() {
        assertThat(jdbcTemplate.queryForList("select id, exectype from databasechangelog where id like '027-%' order by orderexecuted"))
                .extracting(row -> row.get("ID"), row -> row.get("EXECTYPE"))
                .containsExactly(
                        tuple("027-link-daily-stats-date-index", "EXECUTED"),
                        tuple("027-link-daily-breakdown-date-index", "EXECUTED"),
                        tuple("027-url-create-date-index", "EXECUTED"),
                        tuple("027-user-account-create-date-index", "EXECUTED"),
                        tuple("027-link-review-flagged-at-index", "EXECUTED"),
                        tuple("027-url-created-via", "EXECUTED"),
                        tuple("027-url-short-url-unique", "EXECUTED"),
                        tuple("027-reserved-alias", "EXECUTED"));
    }

    @Test
    void addsTheRangeIndexes() {
        assertThat(jdbcTemplate.queryForList("select upper(index_name) from information_schema.indexes", String.class))
                .contains(
                        "IDX_LINK_DAILY_STATS_STAT_DATE",
                        "IDX_LINK_DAILY_BREAKDOWN_DATE_DIMENSION",
                        "IDX_URL_CREATE_DATE",
                        "IDX_USER_ACCOUNT_CREATE_DATE",
                        "IDX_LINK_REVIEW_FLAGGED_AT");
    }

    @Test
    void shortUrlIsUniqueAsStored() {
        insertLink("abc");

        assertThatThrownBy(() -> insertLink("abc")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insertLink("ABC")).isEqualTo(1);
    }

    @Test
    void createdViaIsANullableColumn() {
        insertLink("unknown-channel");
        jdbcTemplate.update("insert into url (short_url, long_url, created_via) values (?, ?, ?)",
                "anonymous-mcp", "https://example.com/anonymous-mcp", "ANONYMOUS_MCP");

        assertThat(createdVia("unknown-channel")).isNull();
        assertThat(createdVia("anonymous-mcp")).isEqualTo("ANONYMOUS_MCP");
    }

    @Test
    void reservedAliasIsUniqueAndNeedsEveryField() {
        jdbcTemplate.update(INSERT_RESERVED_ALIAS, "promo", "r", ADMIN);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_RESERVED_ALIAS, "promo", "r", ADMIN))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_RESERVED_ALIAS, "no-reason", null, ADMIN))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.update(INSERT_RESERVED_ALIAS, "a".repeat(32), "r".repeat(200), ADMIN)).isEqualTo(1);
    }

    @Test
    void uniquenessIsEnforcedOnACleanDatabase() {
        assertThat(shortUrlUniqueIndex.enforced()).isTrue();
    }

    private int insertLink(final String shortUrl) {
        return jdbcTemplate.update("insert into url (short_url, long_url) values (?, ?)", shortUrl, "https://example.com/" + shortUrl);
    }

    private String createdVia(final String shortUrl) {
        return jdbcTemplate.queryForObject("select created_via from url where short_url = ?", String.class, shortUrl);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UrlRepository.class))
    @Import(ShortUrlUniqueIndex.class)
    static class TestApplication {
    }

}
