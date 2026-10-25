package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.ApiKeyCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.McpCounts;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.stats.UsersStatsRepository.AccountCounts;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
@SpringBootTest(
        classes = UsersStatsRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:users-stats;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class UsersStatsRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 10, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final long ROLE_USER = 1;
    private static final long ROLE_ADMIN = 2;

    @Autowired
    private UsersStatsRepository usersStatsRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void countsAccountsAndTheirFlags() {
        account(9201, true, true, true);
        account(9202, false, true, false);
        accountWithDefaults(9203);
        account(9204, true, false, false);

        assertThat(usersStatsRepository.countAccounts()).isEqualTo(new AccountCounts(4, 3, 3, 1));
        assertThatThrownBy(() -> jdbcTemplate.update("insert into user_account (id, email, password, active) values (?, ?, ?, ?)",
                9205, email(9205), "correct horse battery", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void countsAdminsOnce() {
        accountWithDefaults(9301);
        accountWithDefaults(9302);
        accountWithDefaults(9303);
        grant(9301, ROLE_USER);
        grant(9301, ROLE_ADMIN);
        grant(9302, ROLE_USER);

        assertThat(usersStatsRepository.countAdmins()).isEqualTo(1);
    }

    @Test
    void byProviderTreatsNullAsLocal() {
        accountFrom(9401, "google");
        accountFrom(9402, "local");
        accountWithDefaults(9403);
        accountFrom(9404, "github");
        accountWithDefaults(9405);
        accountFrom(9406, "google");
        accountFrom(9407, "github");

        assertThat(usersStatsRepository.countByProvider()).containsExactly(
                new CountEntry("local", 3),
                new CountEntry("github", 2),
                new CountEntry("google", 2));
        assertThatThrownBy(() -> accountFrom(9408, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void signedInSinceUsesLastLogin() {
        final var since = NOW.minusDays(7);
        signedIn(9501, since);
        signedIn(9502, since.minusSeconds(1));
        signedIn(9503, null);
        signedIn(9504, NOW);

        assertThat(usersStatsRepository.countSignedInSince(since)).isEqualTo(2);
        assertThat(usersStatsRepository.countSignedInSince(NOW.minusDays(30))).isEqualTo(3);
    }

    @Test
    void apiKeysSplitActiveAndExpiredAndSumCalls() {
        apiKey(9601, true, null, 10L);
        apiKey(9602, true, TODAY, 5L);
        apiKey(9603, true, TODAY.minusDays(1), 7L);
        apiKey(9604, false, null, 3L);
        apiKey(9605, false, TODAY.minusDays(10), null);
        apiKey(9606, true, TODAY.plusDays(30), 0L);

        assertThat(usersStatsRepository.countApiKeys(TODAY)).isEqualTo(new ApiKeyCounts(3, 2, 25));
    }

    @Test
    void mcpCountsDistinctUsersWithAnUnexpiredToken() {
        client("client-1");
        client("client-2");
        client("client-3");
        authorization("auth-1", "client-1", "ana@example.com", NOW.plusHours(1), NOW.plusDays(30));
        authorization("auth-2", "client-2", "ana@example.com", NOW.minusHours(1), NOW.plusDays(30));
        authorization("auth-3", "client-1", "ben@example.com", NOW.minusDays(2), NOW.minusDays(1));
        authorization("auth-4", "client-3", "cleo@example.com", NOW.minusHours(1), NOW.plusDays(1));
        authorization("auth-5", "client-2", "dora@example.com", NOW, null);
        authorization("auth-6", "client-1", "eve@example.com", null, null);

        assertThat(usersStatsRepository.countMcp(NOW)).isEqualTo(new McpCounts(2, 3));
    }

    @Test
    void anEmptySiteCountsZero() {
        assertThat(usersStatsRepository.countAccounts()).isEqualTo(new AccountCounts(0, 0, 0, 0));
        assertThat(usersStatsRepository.countAdmins()).isZero();
        assertThat(usersStatsRepository.countByProvider()).isEmpty();
        assertThat(usersStatsRepository.countSignedInSince(NOW.minusDays(30))).isZero();
        assertThat(usersStatsRepository.countApiKeys(TODAY)).isEqualTo(new ApiKeyCounts(0, 0, 0));
        assertThat(usersStatsRepository.countMcp(NOW)).isEqualTo(new McpCounts(0, 0));
    }

    private void account(final long id, final boolean active, final boolean emailVerified, final boolean twoFactorEnabled) {
        jdbcTemplate.update("insert into user_account (id, email, password, active, email_verified, two_factor_enabled) "
                        + "values (?, ?, ?, ?, ?, ?)",
                id, email(id), "correct horse battery", active, emailVerified, twoFactorEnabled);
    }

    private void accountWithDefaults(final long id) {
        jdbcTemplate.update("insert into user_account (id, email, password) values (?, ?, ?)", id, email(id), "correct horse battery");
    }

    private void accountFrom(final long id, final String authProvider) {
        jdbcTemplate.update("insert into user_account (id, email, password, auth_provider) values (?, ?, ?, ?)",
                id, email(id), "correct horse battery", authProvider);
    }

    private void signedIn(final long id, final LocalDateTime lastLogin) {
        jdbcTemplate.update("insert into user_account (id, email, password, last_login) values (?, ?, ?, ?)",
                id, email(id), "correct horse battery", lastLogin);
    }

    private void grant(final long userId, final long authorityId) {
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", userId, authorityId);
    }

    private void apiKey(final long id, final boolean active, final LocalDate expirationDate, final Long apiCallsUsed) {
        jdbcTemplate.update("insert into api_key (id, key, active, expiration_date, api_calls_used) values (?, ?, ?, ?, ?)",
                id, "key-" + id, active, expirationDate, apiCallsUsed);
    }

    private void client(final String id) {
        jdbcTemplate.update("""
                        insert into oauth2_registered_client (id, client_id, client_name, client_authentication_methods,
                            authorization_grant_types, scopes, client_settings, token_settings)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""",
                id, id, "App " + id, "none", "authorization_code,refresh_token", "claudeai", "{}", "{}");
    }

    private void authorization(final String id, final String registeredClientId, final String principalName,
                               final LocalDateTime accessTokenExpiresAt, final LocalDateTime refreshTokenExpiresAt) {
        jdbcTemplate.update("""
                        insert into oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type,
                            access_token_expires_at, refresh_token_expires_at)
                        values (?, ?, ?, ?, ?, ?)""",
                id, registeredClientId, principalName, "authorization_code", accessTokenExpiresAt, refreshTokenExpiresAt);
    }

    private static String email(final long id) {
        return "users-" + id + "@example.com";
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @Import(UsersStatsRepository.class)
    static class TestApplication {
    }

}
