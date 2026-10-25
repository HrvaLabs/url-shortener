package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.ReservedAlias;
import cc.hrva.urlshortener.model.Url;
import jakarta.persistence.EntityManager;
import java.util.Set;
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
import static org.assertj.core.api.Assertions.assertThatCode;
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
    private static final String LONG_URL_COLUMN =
            "select %s from information_schema.columns where upper(table_name) = 'URL' and upper(column_name) = 'LONG_URL'";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShortUrlUniqueIndex shortUrlUniqueIndex;

    @Autowired
    private ReservedAliasRepository reservedAliasRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private EntityManager entityManager;

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
                        tuple("027-reserved-alias", "EXECUTED"),
                        tuple("027-url-long-url-length", "EXECUTED"));
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
    void reservedAliasEntityRoundTrips() {
        reservedAliasRepository.save(ReservedAlias.builder().alias("summer").reason("Kept for the summer menu").createdBy(ADMIN).build());
        reservedAliasRepository.save(ReservedAlias.builder().alias("promo").reason("Kept for the spring campaign").createdBy(ADMIN).build());
        entityManager.flush();
        entityManager.clear();

        final var entries = reservedAliasRepository.findAllByOrderByAliasAsc();

        assertThat(entries)
                .extracting(ReservedAlias::getAlias, ReservedAlias::getReason, ReservedAlias::getCreatedBy)
                .containsExactly(
                        tuple("promo", "Kept for the spring campaign", ADMIN),
                        tuple("summer", "Kept for the summer menu", ADMIN));
        assertThat(entries).allSatisfy(entry -> assertThat(entry.getCreatedAt()).isNotNull());
    }

    @Test
    void findsLinksByAliasIgnoringCase() {
        urlRepository.save(Url.builder().shortUrl("Promo").longUrl("https://example.com/promo").build());
        urlRepository.save(Url.builder().shortUrl("promotion").longUrl("https://example.com/promotion").build());

        assertThat(urlRepository.findByLowerShortUrlIn(Set.of("promo")))
                .extracting(Url::getShortUrl)
                .containsExactly("Promo");
    }

    @Test
    void aSecondReservationOfTheSameAliasFailsInSaveItself() {
        reservedAliasRepository.save(ReservedAlias.builder().alias("promo").reason("Kept for the spring campaign").createdBy(ADMIN).build());

        assertThatThrownBy(() -> reservedAliasRepository.save(ReservedAlias.builder().alias("promo").reason("Again").createdBy(ADMIN).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSecondLinkWithTheSameShortUrlIsInsertedOnlyAtFlushWhereItViolatesTheUniqueIndex() {
        urlRepository.saveAndFlush(Url.builder().shortUrl("race").longUrl("https://example.com/first").build());

        assertThatCode(() -> urlRepository.save(Url.builder().shortUrl("race").longUrl("https://example.com/second").build()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> urlRepository.flush())
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        exception -> assertThat(ShortUrlUniqueIndex.violatedBy(exception)).isTrue());
    }

    @Test
    void aDestinationOf2048CharactersIsStoredWhole() {
        final var destination = "https://example.com/" + "a".repeat(2028);
        final var id = urlRepository.saveAndFlush(Url.builder().shortUrl("long-link").longUrl(destination).build()).getId();
        entityManager.clear();

        assertThat(urlRepository.findById(id))
                .get()
                .extracting(Url::getLongUrl)
                .isEqualTo(destination);
        assertThat(destination).hasSize(2048);
    }

    @Test
    void theDestinationColumnIsWideAndStillRequired() {
        assertThat(jdbcTemplate.queryForObject(LONG_URL_COLUMN.formatted("character_maximum_length"), Long.class)).isEqualTo(2048L);
        assertThat(jdbcTemplate.queryForObject(LONG_URL_COLUMN.formatted("is_nullable"), String.class)).isEqualTo("NO");
    }

    @Test
    void aDestinationTooLongForItsColumnIsNotTakenForTheUniqueIndex() {
        final var tooLong = Url.builder().shortUrl("too-long").longUrl("https://example.com/" + "a".repeat(2029)).build();

        assertThatThrownBy(() -> urlRepository.saveAndFlush(tooLong))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        exception -> assertThat(ShortUrlUniqueIndex.violatedBy(exception)).isFalse());
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
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UrlRepository.class, ReservedAliasRepository.class}))
    @Import(ShortUrlUniqueIndex.class)
    static class TestApplication {
    }

}
