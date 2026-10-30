package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.SignInMethodResponse;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.UserSignInMethodRepository;
import cc.hrva.urlshortener.service.SignInMethodService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = DefaultSignInMethodServiceTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:sign-in-methods;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class DefaultSignInMethodServiceTest {

    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2026, 10, 20, 18, 30);
    private static final String BCRYPT_HASH = "$2a$10$" + "h".repeat(53);

    @Autowired
    private SignInMethodService signInMethodService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.saveAndFlush(account("methods@example.com"));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from user_account");
    }

    @Test
    void theFirstUseOfAMethodIsItsFirstAndLastUse() {
        signInMethodService.record(user.getId(), SignInMethod.PASSKEY, FIRST);

        assertThat(signInMethodService.methodsOf(user.getId()))
                .containsExactly(new SignInMethodResponse(SignInMethod.PASSKEY, FIRST, FIRST, null));
    }

    @Test
    void aLaterUseMovesOnlyTheLastUse() {
        signInMethodService.record(user.getId(), SignInMethod.GOOGLE, FIRST);
        signInMethodService.record(user.getId(), SignInMethod.GOOGLE, LATER);

        assertThat(signInMethodService.methodsOf(user.getId()))
                .containsExactly(new SignInMethodResponse(SignInMethod.GOOGLE, FIRST, LATER, null));
    }

    @Test
    void anOlderUseRecordedLateNeverMovesTheLastUseBack() {
        signInMethodService.record(user.getId(), SignInMethod.GITHUB, FIRST);
        signInMethodService.record(user.getId(), SignInMethod.GITHUB, LATER);
        signInMethodService.record(user.getId(), SignInMethod.GITHUB, FIRST.plusDays(1));

        assertThat(signInMethodService.methodsOf(user.getId()))
                .containsExactly(new SignInMethodResponse(SignInMethod.GITHUB, FIRST, LATER, null));
    }

    @Test
    void aRecordWritesNothingButTheSignInMethod() {
        jdbcTemplate.update("update user_account set last_login = ?, two_step_enabled = true where id = ?", FIRST, user.getId());

        signInMethodService.record(user.getId(), SignInMethod.PASSWORD, LATER);

        assertThat(jdbcTemplate.queryForMap("select last_login, two_step_enabled from user_account where id = ?", user.getId()))
                .containsEntry("LAST_LOGIN", Timestamp.valueOf(FIRST))
                .containsEntry("TWO_STEP_ENABLED", true);
    }

    @Test
    void passwordComesFirstAndTheRestInTheOrderFirstUsed() {
        signInMethodService.record(user.getId(), SignInMethod.GITHUB, FIRST.plusDays(2));
        signInMethodService.record(user.getId(), SignInMethod.PASSKEY, FIRST);
        signInMethodService.record(user.getId(), SignInMethod.PASSWORD, LATER);
        signInMethodService.record(user.getId(), SignInMethod.GOOGLE, FIRST.plusDays(1));

        assertThat(signInMethodService.methodsOf(user.getId()))
                .extracting(SignInMethodResponse::method)
                .containsExactly(SignInMethod.PASSWORD, SignInMethod.PASSKEY, SignInMethod.GOOGLE, SignInMethod.GITHUB);
    }

    @Test
    void anAccountWithoutRecordsHasAnEmptyList() {
        assertThat(signInMethodService.methodsOf(user.getId())).isEmpty();
    }

    @Test
    void aPageOfAccountsGetsEachAccountsOwnMethods() {
        final var other = userRepository.saveAndFlush(account("other@example.com"));
        final var withoutRecords = userRepository.saveAndFlush(account("none@example.com"));
        signInMethodService.record(user.getId(), SignInMethod.PASSWORD, FIRST);
        signInMethodService.record(other.getId(), SignInMethod.GOOGLE, LATER);

        final var methods = signInMethodService.methodsOf(List.of(user.getId(), other.getId(), withoutRecords.getId()));

        assertThat(methods.get(user.getId())).containsExactly(new SignInMethodResponse(SignInMethod.PASSWORD, FIRST, FIRST, null));
        assertThat(methods.get(other.getId())).containsExactly(new SignInMethodResponse(SignInMethod.GOOGLE, LATER, LATER, null));
        assertThat(methods.get(withoutRecords.getId())).isEmpty();
    }

    @Test
    void aMethodIsRecordedForAnAccountSavedEarlierInTheSameTransaction() {
        final var created = new TransactionTemplate(transactionManager).execute(status -> {
            final var saved = userRepository.save(account("new@example.com"));
            signInMethodService.record(saved.getId(), SignInMethod.PASSWORD, FIRST);
            return saved;
        });

        assertThat(signInMethodService.methodsOf(created.getId()))
                .containsExactly(new SignInMethodResponse(SignInMethod.PASSWORD, FIRST, FIRST, null));
    }

    @Test
    void twoFirstSignInsAtOnceLeaveOneRowAndBothSucceed() throws Exception {
        final var firstHoldsItsTransaction = new CountDownLatch(1);
        final var secondStarted = new CountDownLatch(1);

        final var first = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            signInMethodService.record(user.getId(), SignInMethod.PASSKEY, FIRST);
            firstHoldsItsTransaction.countDown();
            awaitQuietly(secondStarted);
            sleepQuietly();
        }));
        firstHoldsItsTransaction.await(5, TimeUnit.SECONDS);
        final var second = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            secondStarted.countDown();
            signInMethodService.record(user.getId(), SignInMethod.PASSKEY, LATER);
        }));

        CompletableFuture.allOf(first, second).get(20, TimeUnit.SECONDS);

        assertThat(signInMethodService.methodsOf(user.getId()))
                .containsExactly(new SignInMethodResponse(SignInMethod.PASSKEY, FIRST, LATER, null));
    }

    @Test
    void aTurnedOffMethodListsWhenItWasTurnedOff() {
        signInMethodService.record(user.getId(), SignInMethod.GOOGLE, FIRST);

        assertThat(signInMethodService.turnOff(user.getId(), SignInMethod.GOOGLE, LATER)).isTrue();

        assertThat(signInMethodService.methodsOf(user.getId()))
                .containsExactly(new SignInMethodResponse(SignInMethod.GOOGLE, FIRST, FIRST, LATER));
        assertThat(signInMethodService.isTurnedOff(user.getId(), SignInMethod.GOOGLE)).isTrue();
    }

    @Test
    void turningOffWhatIsAlreadyOffKeepsTheFirstTime() {
        signInMethodService.record(user.getId(), SignInMethod.GITHUB, FIRST);
        signInMethodService.turnOff(user.getId(), SignInMethod.GITHUB, FIRST);

        assertThat(signInMethodService.turnOff(user.getId(), SignInMethod.GITHUB, LATER)).isFalse();

        assertThat(signInMethodService.methodsOf(user.getId()))
                .containsExactly(new SignInMethodResponse(SignInMethod.GITHUB, FIRST, FIRST, FIRST));
    }

    @Test
    void turningBackOnClearsOnlyThatMethod() {
        signInMethodService.record(user.getId(), SignInMethod.GOOGLE, FIRST);
        signInMethodService.record(user.getId(), SignInMethod.GITHUB, FIRST);
        signInMethodService.turnOff(user.getId(), SignInMethod.GOOGLE, LATER);
        signInMethodService.turnOff(user.getId(), SignInMethod.GITHUB, LATER);

        assertThat(signInMethodService.turnOn(user.getId(), SignInMethod.GOOGLE)).isTrue();
        assertThat(signInMethodService.turnOn(user.getId(), SignInMethod.GOOGLE)).isFalse();

        assertThat(signInMethodService.isTurnedOff(user.getId(), SignInMethod.GOOGLE)).isFalse();
        assertThat(signInMethodService.isTurnedOff(user.getId(), SignInMethod.GITHUB)).isTrue();
    }

    @Test
    void aMethodNeverUsedIsNotTurnedOffAndCantBeTurnedOff() {
        assertThat(signInMethodService.turnOff(user.getId(), SignInMethod.GOOGLE, LATER)).isFalse();

        assertThat(signInMethodService.isTurnedOff(user.getId(), SignInMethod.GOOGLE)).isFalse();
        assertThat(signInMethodService.methodsOf(user.getId())).isEmpty();
    }

    @Test
    void aTurnOffNeverTouchesAnotherAccount() {
        final var other = userRepository.saveAndFlush(account("other-off@example.com"));
        signInMethodService.record(user.getId(), SignInMethod.GOOGLE, FIRST);
        signInMethodService.record(other.getId(), SignInMethod.GOOGLE, FIRST);

        signInMethodService.turnOff(user.getId(), SignInMethod.GOOGLE, LATER);

        assertThat(signInMethodService.isTurnedOff(other.getId(), SignInMethod.GOOGLE)).isFalse();
    }

    @Test
    void recordingAUseLeavesATurnedOffMethodOff() {
        signInMethodService.record(user.getId(), SignInMethod.PASSWORD, FIRST);
        signInMethodService.turnOff(user.getId(), SignInMethod.PASSWORD, FIRST.plusDays(1));

        signInMethodService.record(user.getId(), SignInMethod.PASSWORD, LATER);

        assertThat(signInMethodService.methodsOf(user.getId()))
                .containsExactly(new SignInMethodResponse(SignInMethod.PASSWORD, FIRST, LATER, FIRST.plusDays(1)));
    }

    @Test
    void aBcryptHashIsAPasswordWithOrWithoutItsRow() {
        final var withHash = userRepository.saveAndFlush(account("hash@example.com", BCRYPT_HASH));

        assertThat(signInMethodService.passwordSet(withHash)).isTrue();
        signInMethodService.record(withHash.getId(), SignInMethod.PASSWORD, FIRST);
        assertThat(signInMethodService.passwordSet(withHash)).isTrue();
    }

    @Test
    void aTurnedOffPasswordRowMeansNoPassword() {
        final var withHash = userRepository.saveAndFlush(account("off@example.com", BCRYPT_HASH));
        signInMethodService.record(withHash.getId(), SignInMethod.PASSWORD, FIRST);

        signInMethodService.turnOff(withHash.getId(), SignInMethod.PASSWORD, LATER);

        assertThat(signInMethodService.passwordSet(withHash)).isFalse();
    }

    @Test
    void aRandomValueIsNoPasswordEvenWithAPasswordRowOn() {
        signInMethodService.record(user.getId(), SignInMethod.PASSWORD, FIRST);

        assertThat(signInMethodService.passwordSet(user)).isFalse();
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(300);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static User account(final String email) {
        return account(email, "correct horse battery");
    }

    private static User account(final String email, final String password) {
        return User.builder()
                .email(email)
                .password(password)
                .apiKeySlots(4L)
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UserRepository.class, UserSignInMethodRepository.class}))
    @Import(DefaultSignInMethodService.class)
    static class TestApplication {
    }

}
