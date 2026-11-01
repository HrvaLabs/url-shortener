package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.security.ApiKeyTokens;
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
        classes = ResetTokenHashMigrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/reset-token-hash-migration-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:reset-token-hash-migration;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.data.jpa.repositories.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ResetTokenHashMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void everyPlaintextTokenIsDeletedSoAResetInProgressAsksAgain() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from reset_token", Long.class)).isZero();
    }

    @Test
    void thePlaintextColumnIsGoneAndTheHashTakesItsPlace() {
        assertThat(columns("TOKEN")).isZero();
        assertThat(columns("TOKEN_HASH")).isOne();
    }

    @Test
    void aTokenIsStoredByItsHashWhichIsRequiredAndUnique() {
        final var hash = ApiKeyTokens.hash("a-new-reset-token");
        jdbcTemplate.update("insert into reset_token (id, user_id, token_hash, expiration_date, active) values (10, 1, ?, '2026-11-01 23:00:00', true)", hash);

        assertThat(jdbcTemplate.queryForObject("select token_hash from reset_token where id = 10", String.class)).isEqualTo(hash).hasSize(64);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into reset_token (id, user_id, token_hash, expiration_date, active) values (11, 1, ?, '2026-11-01 23:00:00', true)", hash))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into reset_token (id, user_id, token_hash, expiration_date, active) values (12, 1, null, '2026-11-01 23:00:00', true)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long columns(final String name) {
        return jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where upper(table_name) = 'RESET_TOKEN' and upper(column_name) = ?", Long.class, name);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

}
