package cc.hrva.urlshortener.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        classes = ShortUrlDuplicatesRenamedMigrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/short-url-duplicates-left-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:short-url-duplicates-renamed;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.data.jpa.repositories.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ShortUrlDuplicatesRenamedMigrationTest {

    private static final String NOT_ENFORCED = "short_url uniqueness not enforced";
    private static final String UNIQUE_SHORT_URL_INDEXES = """
            select count(*) from information_schema.indexes i
            join information_schema.index_columns c
              on c.index_schema = i.index_schema and c.index_name = i.index_name and c.table_name = i.table_name
            where i.table_name = 'URL' and c.column_name = 'SHORT_URL' and i.index_type_name = 'UNIQUE INDEX'""";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShortUrlUniqueIndex shortUrlUniqueIndex;

    @Test
    void the030ChangesetsRan() {
        assertThat(jdbcTemplate.queryForList("select id, exectype from databasechangelog where id like '030-%' order by orderexecuted"))
                .extracting(row -> row.get("ID"), row -> row.get("EXECTYPE"))
                .containsExactly(
                        tuple("030-url-short-url-rename-duplicates", "EXECUTED"),
                        tuple("030-url-short-url-unique", "EXECUTED"));
        assertThat(jdbcTemplate.queryForObject("select exectype from databasechangelog where id = '027-url-short-url-unique'", String.class))
                .isEqualTo("MARK_RAN");
    }

    @Test
    void theRowTheRedirectServesKeepsItsAliasAndTheOthersAreRenamed() {
        assertThat(jdbcTemplate.queryForList("select id, short_url from url where id < 70 order by id"))
                .extracting(row -> row.get("ID"), row -> row.get("SHORT_URL"))
                .containsExactly(
                        tuple(11L, "two-off~11"),
                        tuple(12L, "two-off"),
                        tuple(21L, "one-on"),
                        tuple(22L, "one-on~22"),
                        tuple(31L, "two-on~31"),
                        tuple(32L, "two-on"),
                        tuple(41L, "three~41"),
                        tuple(42L, "three"),
                        tuple(43L, "three~43"),
                        tuple(51L, "a".repeat(252) + "~51"),
                        tuple(52L, "a".repeat(255)),
                        tuple(61L, "solo"));
    }

    @Test
    void aRenamedAliasTooLongForTheColumnIsTrimmedBeforeTheId() {
        assertThat(alias(51L)).hasSize(255).endsWith("~51");
    }

    @Test
    void aliasesDifferingOnlyInCaseAreNotDuplicates() {
        assertThat(alias(71L)).isEqualTo("Case");
        assertThat(alias(72L)).isEqualTo("case");
    }

    @Test
    void theRenamedRowsKeepTheirState() {
        assertThat(jdbcTemplate.queryForObject("select active from url where id = 31", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("select active from url where id = 22", Boolean.class)).isFalse();
    }

    @Test
    void enforcedAndQuietAtStartup(final CapturedOutput output) {
        assertThat(shortUrlUniqueIndex.enforced()).isTrue();

        shortUrlUniqueIndex.warnIfNotEnforced();

        assertThat(output).doesNotContain(NOT_ENFORCED);
    }

    @Test
    void exactlyOneUniqueIndexGuardsTheShortUrl() {
        assertThat(jdbcTemplate.queryForObject(UNIQUE_SHORT_URL_INDEXES, Integer.class)).isEqualTo(1);
    }

    @Test
    void aTakenShortUrlCanNoLongerBeInsertedAgain() {
        insertLink(1000L, "taken");

        assertThatThrownBy(() -> insertLink(1001L, "taken"))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        exception -> assertThat(exception.getMessage()).containsIgnoringCase("ux_url_short_url"));
    }

    private String alias(final long id) {
        return jdbcTemplate.queryForObject("select short_url from url where id = ?", String.class, id);
    }

    private void insertLink(final long id, final String shortUrl) {
        jdbcTemplate.update("insert into url (id, short_url, long_url) values (?, ?, ?)", id, shortUrl, "https://example.com/" + shortUrl);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ShortUrlUniqueIndex.class)
    static class TestApplication {
    }

}
