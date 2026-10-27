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
        classes = ShortUrlRenamedByHandMigrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/short-url-renamed-by-hand-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:short-url-renamed-by-hand;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.data.jpa.repositories.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ShortUrlRenamedByHandMigrationTest {

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
    void theRenameFindsNothingToDo() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from url where short_url like '%~%'", Integer.class)).isZero();
        assertThat(alias(1L)).isEqualTo("dup-old");
        assertThat(alias(2L)).isEqualTo("dup");
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
