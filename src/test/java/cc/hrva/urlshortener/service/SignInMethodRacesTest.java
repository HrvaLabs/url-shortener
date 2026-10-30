package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.RemovePasswordDto;
import cc.hrva.urlshortener.exception.LastSignInMethodException;
import cc.hrva.urlshortener.listener.SignInMethodChangedEmailListener;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.UserSignInMethodRepository;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.security.RecentSignIn;
import cc.hrva.urlshortener.security.StoredPasswords;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultSignInMethodChangeService;
import cc.hrva.urlshortener.service.impl.DefaultSignInMethodService;
import cc.hrva.urlshortener.service.signin.SignInMethodGuard;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import cc.hrva.urlshortener.validator.impl.DefaultAuthValidator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = SignInMethodRacesTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:sign-in-method-races;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class SignInMethodRacesTest {

    private static final String EMAIL = "two-tabs@example.com";
    private static final String PASSWORD = "correct horse battery";
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 1, 9, 0);

    @Autowired
    private SignInMethodChangeService signInMethodChangeService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.saveAndFlush(User.builder()
                .email(EMAIL)
                .password(new BCryptPasswordEncoder(4).encode(PASSWORD))
                .apiKeySlots(4L)
                .authProvider("local")
                .active(true)
                .emailVerified(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from user_account");
    }

    @Test
    void twoTabsRemovingThePasswordAndTheOnlyProviderLeaveOneOfThem() throws Exception {
        method(SignInMethod.PASSWORD);
        method(SignInMethod.GOOGLE);

        final var outcomes = race(
                () -> signInMethodChangeService.removePassword(new RemovePasswordDto(PASSWORD)),
                () -> signInMethodChangeService.turnOff(SignInMethod.GOOGLE));

        assertThat(outcomes).containsExactly("done", "refused");
        assertThat(StoredPasswords.isSet(jdbcTemplate.queryForObject("select password from user_account where id = ?", String.class, user.getId())))
                .isFalse();
        assertThat(turnedOff()).containsExactly("PASSWORD");
        assertThat(jdbcTemplate.queryForList("select action from audit_log", String.class)).containsExactly("REMOVE_PASSWORD");
    }

    @Test
    void twoTabsTurningOffTheOnlyTwoProvidersLeaveOneOn() throws Exception {
        userRepository.replacePassword(user.getId(), StoredPasswords.none());
        method(SignInMethod.GOOGLE);
        method(SignInMethod.GITHUB);

        final var outcomes = race(
                () -> signInMethodChangeService.turnOff(SignInMethod.GITHUB),
                () -> signInMethodChangeService.turnOff(SignInMethod.GOOGLE));

        assertThat(outcomes).containsExactly("done", "refused");
        assertThat(turnedOff()).containsExactly("GITHUB");
    }

    private List<String> race(final Supplier<?> firstTab, final Supplier<?> secondTab) throws Exception {
        final var firstHoldsItsTransaction = new CountDownLatch(1);
        final var secondStarted = new CountDownLatch(1);

        final var first = CompletableFuture.supplyAsync(() -> asTheUser(() -> new TransactionTemplate(transactionManager).execute(status -> {
            final var outcome = outcomeOf(firstTab);
            firstHoldsItsTransaction.countDown();
            awaitQuietly(secondStarted);
            sleepQuietly();
            return outcome;
        })));
        assertThat(firstHoldsItsTransaction.await(5, TimeUnit.SECONDS)).isTrue();
        final var second = CompletableFuture.supplyAsync(() -> asTheUser(() -> {
            secondStarted.countDown();
            return outcomeOf(secondTab);
        }));

        return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    }

    private static String outcomeOf(final Supplier<?> change) {
        try {
            change.get();
            return "done";
        } catch (final LastSignInMethodException exception) {
            return "refused";
        }
    }

    private static <T> T asTheUser(final Supplier<T> work) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(EMAIL, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void method(final SignInMethod method) {
        jdbcTemplate.update("insert into user_sign_in_method (user_id, method, first_used_at, last_used_at) values (?, ?, ?, ?)",
                user.getId(), method.name(), FIRST, FIRST);
    }

    private List<String> turnedOff() {
        return jdbcTemplate.queryForList("select method from user_sign_in_method where user_id = ? and turned_off_at is not null order by method",
                String.class, user.getId());
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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, UserSignInMethodRepository.class, PasskeyCredentialRepository.class, AuditLogRepository.class}))
    @Import({
            AccountLock.class,
            RecentSignIn.class,
            DefaultAuthValidator.class,
            Reauthentication.class,
            DefaultAuditLogService.class,
            DefaultSignInMethodService.class,
            SignInMethodGuard.class,
            DefaultSignInMethodChangeService.class,
            SignInMethodChangedEmailListener.class
    })
    static class TestApplication {

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

    }

}
