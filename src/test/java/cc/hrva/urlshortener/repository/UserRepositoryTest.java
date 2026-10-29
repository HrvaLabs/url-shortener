package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.User;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = UserRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:user-repository;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.saveAndFlush(User.builder()
                .email("two-writers@example.com")
                .password("correct horse battery")
                .apiKeySlots(4L)
                .twoStepEnabled(true)
                .twoFactorEnabled(true)
                .twoFactorSecret("SECRET")
                .build());
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(user.getId());
    }

    @Test
    void aLastLoginSaveFromAnEarlierCopyKeepsTheTwoStepChangeCommittedMeanwhile() {
        final var signIn = new TransactionTemplate(transactionManager);
        final var settings = new TransactionTemplate(transactionManager);
        settings.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        signIn.executeWithoutResult(status -> {
            final var loadedBySignIn = userRepository.findById(user.getId()).orElseThrow();
            settings.executeWithoutResult(other -> {
                final var loadedBySettings = userRepository.findById(user.getId()).orElseThrow();
                loadedBySettings.setTwoStepEnabled(false);
                loadedBySettings.setTwoFactorEnabled(false);
                loadedBySettings.setTwoFactorSecret(null);
                userRepository.save(loadedBySettings);
            });

            loadedBySignIn.userLoggedIn();
            userRepository.save(loadedBySignIn);
        });

        final var stored = jdbcTemplate.queryForMap(
                "select two_step_enabled, two_factor_enabled, two_factor_secret, last_login from user_account where id = ?", user.getId());
        assertThat(stored.get("two_step_enabled")).isEqualTo(false);
        assertThat(stored.get("two_factor_enabled")).isEqualTo(false);
        assertThat(stored.get("two_factor_secret")).isNull();
        assertThat(stored.get("last_login")).isNotNull();
    }

    @Test
    void confirmingAnEmailIsVisibleToTheSameTransactionRightAway() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            final var loaded = userRepository.findById(user.getId()).orElseThrow();
            assertThat(loaded.getEmailVerified()).isFalse();

            assertThat(userRepository.confirmEmailAndReplacePassword(user.getId(), "replaced-random-value")).isOne();

            final var reread = userRepository.findById(user.getId()).orElseThrow();
            assertThat(reread.getEmailVerified()).isTrue();
            assertThat(reread.getPassword()).isEqualTo("replaced-random-value");
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserRepository.class))
    static class TestApplication {
    }

}
