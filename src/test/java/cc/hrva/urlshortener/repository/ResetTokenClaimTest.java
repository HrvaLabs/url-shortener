package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.security.ApiKeyTokens;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ResetTokenClaimTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:reset-token-claim;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ResetTokenClaimTest {

    private static final long OWNER = 880L;
    private static final long TOKEN = 890L;
    private static final LocalDateTime EXPIRES = LocalDateTime.of(2026, 11, 1, 11, 0);

    @Autowired
    private ResetTokenRepository resetTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void anActiveToken() {
        jdbcTemplate.update("insert into user_account (id, email, password, active) values (?, 'reset@example.com', 'secret-hash', true)", OWNER);
        jdbcTemplate.update("insert into reset_token (id, user_id, token_hash, expiration_date, active) values (?, ?, ?, ?, true)",
                TOKEN, OWNER, ApiKeyTokens.hash("the-token"), EXPIRES);
    }

    @AfterEach
    void removeTheTokenAndItsOwner() {
        jdbcTemplate.update("delete from reset_token where user_id = ?", OWNER);
        jdbcTemplate.update("delete from user_account where id = ?", OWNER);
    }

    @Test
    void aTokenIsFoundByItsHashForItsAccount() {
        final var owner = userRepository.findById(OWNER).orElseThrow();

        assertThat(resetTokenRepository.findByUserAndTokenHashAndActiveTrue(owner, ApiKeyTokens.hash("the-token")))
                .hasValueSatisfying(token -> assertThat(token.getId()).isEqualTo(TOKEN));
        assertThat(resetTokenRepository.findByUserAndTokenHashAndActiveTrue(owner, ApiKeyTokens.hash("another-token"))).isEmpty();
    }

    @Test
    void aTokenIsClaimedOnce() {
        assertThat(inTransaction(() -> resetTokenRepository.claim(TOKEN, EXPIRES.minusMinutes(1)))).isOne();
        assertThat(inTransaction(() -> resetTokenRepository.claim(TOKEN, EXPIRES.minusMinutes(1)))).isZero();
        assertThat(jdbcTemplate.queryForObject("select active from reset_token where id = ?", Boolean.class, TOKEN)).isFalse();
    }

    @Test
    void anExpiredTokenCantBeClaimed() {
        assertThat(inTransaction(() -> resetTokenRepository.claim(TOKEN, EXPIRES))).isZero();
        assertThat(inTransaction(() -> resetTokenRepository.claim(TOKEN, EXPIRES.plusMinutes(1)))).isZero();
    }

    @Test
    void concurrentClaimsLetOnlyOneThrough() {
        final var claims = IntStream.range(0, 20)
                .mapToObj(attempt -> CompletableFuture.supplyAsync(() -> inTransaction(() -> resetTokenRepository.claim(TOKEN, EXPIRES.minusMinutes(5)))))
                .toList();

        assertThat(claims.stream().mapToInt(CompletableFuture::join).sum()).isOne();
    }

    private int inTransaction(final java.util.function.Supplier<Integer> claim) {
        return new TransactionTemplate(transactionManager).execute(status -> claim.get());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, ResetTokenRepository.class}))
    static class TestApplication {
    }

}
