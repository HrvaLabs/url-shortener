package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.converter.UserUpdateDtoToUserConverter;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.ResetTokenRepository;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.VerificationTokenRepository;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.service.bio.BioAccountData;
import cc.hrva.urlshortener.service.impl.DefaultModerationService;
import cc.hrva.urlshortener.service.impl.DefaultSendingEmailService;
import cc.hrva.urlshortener.service.impl.DefaultUserService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        classes = ModerationUserWritesTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:moderation-user-writes;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ModerationUserWritesTest {

    private static final long MEMBER = 720L;
    private static final String MEMBER_EMAIL = "member@example.com";
    private static final String ADMIN = "admin@hrva.cc";
    private static final LocalDateTime LOADED_LOGIN = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LocalDateTime CONCURRENT_LOGIN = LocalDateTime.of(2026, 10, 27, 11, 30);

    private final AtomicBoolean concurrentChangeDone = new AtomicBoolean();

    @Autowired
    private ModerationService moderationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private UserRepository userRepository;

    @MockitoSpyBean
    private ApiKeyRepository apiKeyRepository;

    @MockitoBean
    private LinkCheckService linkCheckService;

    @MockitoBean
    private ReviewQueueService reviewQueueService;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private TaskScheduler taskScheduler;

    @MockitoBean
    private AppProperties appProperties;

    @MockitoBean
    private Reauthentication reauthentication;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private EmailLogRepository emailLogRepository;

    @MockitoBean
    private ResetTokenService resetTokenService;

    @MockitoBean
    private ResetTokenRepository resetTokenRepository;

    @MockitoBean
    private TwoFactorRecoveryCodeRepository twoFactorRecoveryCodeRepository;

    @MockitoBean
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @MockitoBean
    private BioAccountData bioAccountData;

    @MockitoBean
    private DefaultSendingEmailService sendingEmailService;

    @MockitoBean
    private VerificationTokenService verificationTokenService;

    @MockitoBean
    private VerificationTokenRepository verificationTokenRepository;

    @MockitoBean
    private ConnectedAppsService connectedAppsService;

    @MockitoBean
    private ScammerSignalService scammerSignalService;

    @BeforeEach
    void changeTheAccountRightAfterModerationLoadsIt() {
        doAnswer(invocation -> {
            final var loaded = mockingDetails(userRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            if (concurrentChangeDone.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> jdbcTemplate.update(
                        "update user_account set last_login = ?, password = 'changed-hash' where id = ?", CONCURRENT_LOGIN, MEMBER)).join();
            }
            return loaded;
        }).when(userRepository).findById(MEMBER);
    }

    @AfterEach
    void removeTheMember() {
        jdbcTemplate.update("delete from api_key where owner_id = ?", MEMBER);
        jdbcTemplate.update("delete from user_account_authorities where user_id = ?", MEMBER);
        jdbcTemplate.update("delete from user_account where id = ?", MEMBER);
    }

    @Test
    void reactivatingAUserKeepsASignInAndAPasswordChangeThatLandedAfterTheAccountWasLoaded() {
        member(false);

        moderationService.reactivateUser(MEMBER, ADMIN, "admin");

        assertThat(concurrentChangeDone).isTrue();
        assertThat(jdbcTemplate.queryForMap("select active, last_login, password from user_account where id = ?", MEMBER))
                .containsEntry("ACTIVE", true)
                .containsEntry("LAST_LOGIN", Timestamp.valueOf(CONCURRENT_LOGIN))
                .containsEntry("PASSWORD", "changed-hash");
    }

    @Test
    void blockingAUserKeepsASignInAndAPasswordChangeThatLandedAfterTheAccountWasLoaded() {
        member(true);

        moderationService.blockUser(MEMBER, ADMIN, "admin", null);

        assertThat(concurrentChangeDone).isTrue();
        assertThat(jdbcTemplate.queryForMap("select active, last_login, password from user_account where id = ?", MEMBER))
                .containsEntry("ACTIVE", false)
                .containsEntry("LAST_LOGIN", Timestamp.valueOf(CONCURRENT_LOGIN))
                .containsEntry("PASSWORD", "changed-hash");
    }

    @Test
    void blockingAUserTurnsItsActiveKeysOffInOneUpdateAndKeepsTheirCounts() {
        member(true);
        jdbcTemplate.update("""
                insert into api_key (id, "KEY", owner_id, api_calls_limit, api_calls_used, active)
                values (731, 'members-first-key', ?, 100, 3, true), (732, 'members-second-key', ?, 100, 9, true)""", MEMBER, MEMBER);

        moderationService.blockUser(MEMBER, ADMIN, "admin", null);

        assertThat(jdbcTemplate.queryForList("select active, api_calls_used from api_key where owner_id = ? order by id", MEMBER))
                .containsExactly(Map.of("ACTIVE", false, "API_CALLS_USED", 3L), Map.of("ACTIVE", false, "API_CALLS_USED", 9L));
        verify(apiKeyRepository).deactivateActiveByOwnerId(MEMBER);
        verify(apiKeyRepository, never()).saveAll(any());
    }

    private void member(final boolean active) {
        jdbcTemplate.update("insert into user_account (id, email, password, active, last_login) values (?, ?, 'secret-hash', ?, ?)",
                MEMBER, MEMBER_EMAIL, active, LOADED_LOGIN);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, 1)", MEMBER);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, UserRepository.class, ApiKeyRepository.class, DomainRuleRepository.class}))
    @Import({DefaultModerationService.class, DefaultUserService.class, UserToUserDtoConverter.class, UserUpdateDtoToUserConverter.class})
    static class TestApplication {
    }

}
