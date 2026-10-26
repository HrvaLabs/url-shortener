package cc.hrva.urlshortener.service.twostep;

import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import java.util.Optional;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = AccountLockTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:account-lock;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=200",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AccountLockTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountLock accountLock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private TransactionTemplate otherTransaction;
    private User user;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        otherTransaction = new TransactionTemplate(transactionManager);
        otherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        user = userRepository.saveAndFlush(User.builder()
                .email("locked@example.com")
                .password("correct horse battery")
                .apiKeySlots(4L)
                .build());
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(user.getId());
    }

    @Test
    void findByIdForUpdateHoldsTheRowUntilTheTransactionEnds() {
        transaction.executeWithoutResult(status -> {
            assertThat(userRepository.findByIdForUpdate(user.getId())).isPresent();

            assertThatThrownBy(() -> otherTransaction.executeWithoutResult(other -> userRepository.findByIdForUpdate(user.getId())))
                    .isInstanceOf(PessimisticLockingFailureException.class);
            final String readWithoutALock = otherTransaction.execute(other -> userRepository.findById(user.getId()).orElseThrow().getEmail());
            assertThat(readWithoutALock).isEqualTo("locked@example.com");
        });

        final Optional<User> lockedAfterwards = otherTransaction.execute(other -> userRepository.findByIdForUpdate(user.getId()));
        assertThat(lockedAfterwards).isPresent();
    }

    @Test
    void aPlainReadTakesNoLock() {
        transaction.executeWithoutResult(status -> {
            assertThat(userRepository.findById(user.getId())).isPresent();

            final Optional<User> lockedByTheOther = otherTransaction.execute(other -> userRepository.findByIdForUpdate(user.getId()));
            assertThat(lockedByTheOther).isPresent();
        });
    }

    @Test
    void theAccountIsReadAgainOnceLockedEvenWhenItWasLoadedBefore() {
        transaction.executeWithoutResult(status -> {
            final var loadedBefore = userRepository.findByEmail("locked@example.com").orElseThrow();
            otherTransaction.executeWithoutResult(other ->
                    jdbcTemplate.update("update user_account set two_step_enabled = true where id = ?", user.getId()));

            assertThat(userRepository.findByIdForUpdate(user.getId()).orElseThrow().getTwoStepEnabled()).isFalse();

            final var locked = accountLock.lock(loadedBefore);

            assertThat(locked.getId()).isEqualTo(user.getId());
            assertThat(locked.getTwoStepEnabled()).isTrue();
        });
    }

    @Test
    void aDetachedAccountIsLockedAndReadFresh() {
        jdbcTemplate.update("update user_account set two_step_enabled = true where id = ?", user.getId());

        final var locked = transaction.execute(status -> {
            final var lockedAccount = accountLock.lock(user);
            assertThatThrownBy(() -> otherTransaction.executeWithoutResult(other -> userRepository.findByIdForUpdate(user.getId())))
                    .isInstanceOf(PessimisticLockingFailureException.class);

            return lockedAccount;
        });

        assertThat(locked.getTwoStepEnabled()).isTrue();
        assertThat(user.getTwoStepEnabled()).isFalse();
    }

    @Test
    void anAccountDeletedMeanwhileIsNotAuthenticated() {
        final var gone = User.builder().id(-1L).build();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> accountLock.lock(gone)))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Not authenticated");
    }

    @Test
    void aLockOutsideAWriteTransactionIsRefused() {
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> accountLock.lock(user))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> accountLock.lock(user)))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessage("Locking an account needs a read-write transaction");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserRepository.class))
    @Import(AccountLock.class)
    static class TestApplication {
    }

}
