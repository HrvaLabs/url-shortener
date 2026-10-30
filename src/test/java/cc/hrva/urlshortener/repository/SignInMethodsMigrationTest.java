package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
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
        classes = SignInMethodsMigrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/sign-in-methods-migration-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:sign-in-methods-migration;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.data.jpa.repositories.enabled=false",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class SignInMethodsMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aPasswordAccountGetsPasswordFromItsCreateDateToItsLastLogin() {
        assertThat(methodsOf("local@example.com")).containsExactly(Map.entry("PASSWORD",
                new Uses(LocalDateTime.of(2025, 1, 2, 3, 4, 5), LocalDateTime.of(2026, 6, 7, 8, 9, 10))));
    }

    @Test
    void anAccountThatNeverSignedInIsLastUsedAtItsCreateDate() {
        assertThat(methodsOf("never-signed-in@example.com")).containsExactly(Map.entry("PASSWORD",
                new Uses(LocalDateTime.of(2025, 2, 3, 4, 5, 6), LocalDateTime.of(2025, 2, 3, 4, 5, 6))));
    }

    @Test
    void aGoogleAccountGetsGoogle() {
        assertThat(methodsOf("google@example.com")).containsExactly(Map.entry("GOOGLE",
                new Uses(LocalDateTime.of(2025, 3, 4, 5, 6, 7), LocalDateTime.of(2026, 7, 8, 9, 10, 11))));
    }

    @Test
    void aGithubAccountGetsGithub() {
        assertThat(methodsOf("github@example.com")).containsExactly(Map.entry("GITHUB",
                new Uses(LocalDateTime.of(2025, 4, 5, 6, 7, 8), LocalDateTime.of(2025, 4, 5, 6, 7, 8))));
    }

    @Test
    void aSecondRowForTheSameMethodIsRefused() {
        final var userId = idOf("local@example.com");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into user_sign_in_method (user_id, method, first_used_at, last_used_at) values (?, 'PASSWORD', current_timestamp, current_timestamp)",
                userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAnAccountDeletesItsSignInMethods() {
        jdbcTemplate.update("insert into user_account (email, password, auth_provider) values ('deleted@example.com', 'x', 'local')");
        final var userId = idOf("deleted@example.com");
        jdbcTemplate.update(
                "insert into user_sign_in_method (user_id, method, first_used_at, last_used_at) values (?, 'PASSKEY', current_timestamp, current_timestamp)",
                userId);

        jdbcTemplate.update("delete from user_account where id = ?", userId);

        assertThat(jdbcTemplate.queryForObject("select count(*) from user_sign_in_method where user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    void everyBackfilledMethodStartsTurnedOn() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from user_sign_in_method where turned_off_at is not null", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from user_sign_in_method", Long.class)).isPositive();
    }

    @Test
    void aMethodCanBeTurnedOffAndBackOn() {
        final var userId = idOf("google@example.com");
        final var turnedOffAt = LocalDateTime.of(2026, 10, 30, 12, 0);

        jdbcTemplate.update("update user_sign_in_method set turned_off_at = ? where user_id = ?", turnedOffAt, userId);
        assertThat(jdbcTemplate.queryForObject("select turned_off_at from user_sign_in_method where user_id = ?", LocalDateTime.class, userId))
                .isEqualTo(turnedOffAt);

        jdbcTemplate.update("update user_sign_in_method set turned_off_at = null where user_id = ?", userId);
        assertThat(jdbcTemplate.queryForObject("select turned_off_at from user_sign_in_method where user_id = ?", LocalDateTime.class, userId))
                .isNull();
    }

    private Map<String, Uses> methodsOf(final String email) {
        final var methods = new LinkedHashMap<String, Uses>();
        jdbcTemplate.query("""
                        select m.method, m.first_used_at, m.last_used_at from user_sign_in_method m
                        join user_account u on u.id = m.user_id where u.email = ? order by m.method
                        """,
                (rs, rowNum) -> methods.put(rs.getString(1),
                        new Uses(rs.getTimestamp(2).toLocalDateTime(), rs.getTimestamp(3).toLocalDateTime())),
                email);
        return methods;
    }

    private Long idOf(final String email) {
        return jdbcTemplate.queryForObject("select id from user_account where email = ?", Long.class, email);
    }

    private record Uses(LocalDateTime firstUsedAt, LocalDateTime lastUsedAt) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

}
