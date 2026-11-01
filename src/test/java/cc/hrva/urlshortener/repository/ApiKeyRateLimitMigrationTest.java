package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.security.ApiKeyTokens;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = ApiKeyRateLimitMigrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/api-key-rate-limit-migration-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:api-key-rate-limit-migration;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.data.jpa.repositories.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ApiKeyRateLimitMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void theKeysTheOldCapSwitchedOffAreDeletedAndEveryOtherKeyStays() {
        assertThat(jdbcTemplate.queryForList("select key_hash from api_key order by id", String.class))
                .containsExactly(ApiKeyTokens.hash("revoked-early"), ApiKeyTokens.hash("active-at-its-limit"), ApiKeyTokens.hash("revoked-without-a-limit"),
                        ApiKeyTokens.hash("0b7c4f2e-6a1d-4e8b-9f3a-2c5d7e9f1a3b"));
    }

    @Test
    void everyKeptKeyIsHashedWithItsFirstSixCharactersAsItsPrefix() {
        assertThat(jdbcTemplate.queryForList("select key_prefix from api_key order by id", String.class))
                .containsExactly("revoke", "active", "revoke", "0b7c4f");
        assertThat(jdbcTemplate.queryForObject("select key_hash from api_key where id = 6", String.class))
                .isEqualTo(ApiKeyTokens.hash("0b7c4f2e-6a1d-4e8b-9f3a-2c5d7e9f1a3b"))
                .hasSize(64);
    }

    @Test
    void thePlaintextColumnIsGone() {
        assertThat(columns("KEY")).isZero();
    }

    @Test
    void twoKeysCannotShareAHashAndEveryKeyHasOne() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, active) values (20, ?, 'revoke', 1, 0, true)",
                ApiKeyTokens.hash("revoked-early")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, active) values (21, null, 'nohash', 1, 0, true)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theCallLimitColumnIsGone() {
        assertThat(columns("API_CALLS_LIMIT")).isZero();
    }

    @Test
    void theUsageStaysAndEveryKeyStartsNeverUsed() {
        assertThat(jdbcTemplate.queryForObject("select api_calls_used from api_key where id = 5", Long.class)).isEqualTo(1000L);
        assertThat(columns("LAST_USED_AT")).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from api_key where last_used_at is not null", Long.class)).isZero();
    }

    @Test
    void aLastUseCanBeRecorded() {
        final var usedAt = LocalDateTime.of(2026, 10, 30, 12, 0);

        jdbcTemplate.update("update api_key set last_used_at = ? where id = 4", usedAt);

        assertThat(jdbcTemplate.queryForObject("select last_used_at from api_key where id = 4", LocalDateTime.class))
                .isEqualTo(usedAt);
    }

    @Test
    void theExpiryIsATimestampAndAnExistingDateBecomesItsMidnight() {
        assertThat(jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns where upper(table_name) = 'API_KEY' and upper(column_name) = 'EXPIRATION_DATE'",
                String.class)).isEqualTo("TIMESTAMP");
        assertThat(jdbcTemplate.queryForObject("select expiration_date from api_key where id = 3", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0));
    }

    @Test
    void anExpiryKeepsItsTimeOfDay() {
        final var expiry = LocalDateTime.of(2027, 1, 1, 23, 50, 7);

        jdbcTemplate.update("update api_key set expiration_date = ? where id = 4", expiry);

        assertThat(jdbcTemplate.queryForObject("select expiration_date from api_key where id = 4", LocalDateTime.class)).isEqualTo(expiry);
    }

    private Long columns(final String name) {
        return jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where upper(table_name) = 'API_KEY' and upper(column_name) = ?", Long.class, name);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

}
