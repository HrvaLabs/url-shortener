package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.ApiKeyUpdateDtoToApiKeyConverter;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ApiKeyTokens;
import cc.hrva.urlshortener.service.impl.DefaultApiKeyService;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import java.time.LocalDateTime;
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
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

@SpringBootTest(
        classes = ApiKeyWritesTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:api-key-writes;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ApiKeyWritesTest {

    private static final long OWNER = 760L;
    private static final long KEY = 770L;
    private static final LocalDateTime EXPIRES = LocalDateTime.of(2027, 4, 27, 12, 0);

    private final AtomicBoolean concurrentUseDone = new AtomicBoolean();

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private ApiKeyRepository apiKeyRepository;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AppProperties appProperties;

    @MockitoBean
    private ApiKeyValidator apiKeyValidator;

    @MockitoBean
    private ApiKeyUpdateDtoToApiKeyConverter apiKeyUpdateDtoToApiKeyConverter;

    @BeforeEach
    void useTheKeyRightAfterTheRevokeLoadsIt() {
        jdbcTemplate.update("insert into user_account (id, email, password, active) values (?, 'keys@example.com', 'secret-hash', true)", OWNER);
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, expiration_date, active)
                values (?, ?, 'revoke', ?, 4, ?, true)""", KEY, ApiKeyTokens.hash("revoked-while-used"), OWNER, EXPIRES);
        final var useCommits = new TransactionTemplate(transactionManager);
        useCommits.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            final var loaded = mockingDetails(apiKeyRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            if (concurrentUseDone.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> useCommits.executeWithoutResult(
                        status -> assertThat(apiKeyRepository.recordRequest(KEY, LocalDateTime.now())).isOne())).join();
            }
            return loaded;
        }).when(apiKeyRepository).findById(KEY);
    }

    @AfterEach
    void removeTheKeyAndItsOwner() {
        jdbcTemplate.update("delete from api_key where owner_id = ?", OWNER);
        jdbcTemplate.update("delete from user_account where id = ?", OWNER);
    }

    @Test
    void aRevokeWritesOnlyActiveSoAUseCountedAfterTheKeyWasLoadedStays() {
        apiKeyService.revokeApiKey(KEY);

        assertThat(concurrentUseDone).isTrue();
        assertThat(jdbcTemplate.queryForMap("select active, api_calls_used, last_used_at from api_key where id = ?", KEY))
                .containsEntry("ACTIVE", false)
                .containsEntry("API_CALLS_USED", 5L)
                .extractingByKey("LAST_USED_AT").isNotNull();
    }

    @Test
    void aRequestCountsWithOneTargetedUpdateOutsideAnyTransaction() {
        final var at = LocalDateTime.of(2026, 10, 30, 23, 0);

        assertThat(apiKeyRepository.recordRequest(KEY, at)).isOne();

        assertThat(jdbcTemplate.queryForMap("select api_calls_used, last_used_at, active from api_key where id = ?", KEY))
                .containsEntry("API_CALLS_USED", 5L)
                .containsEntry("ACTIVE", true)
                .satisfies(row -> assertThat(((java.sql.Timestamp) row.get("LAST_USED_AT")).toLocalDateTime()).isEqualTo(at));
    }

    @Test
    void concurrentRequestsAreEachCounted() {
        final var requests = java.util.stream.IntStream.range(0, 40)
                .mapToObj(request -> CompletableFuture.supplyAsync(() -> apiKeyRepository.recordRequest(KEY, LocalDateTime.now())))
                .toList();

        assertThat(requests.stream().map(CompletableFuture::join)).containsOnly(1);
        assertThat(jdbcTemplate.queryForObject("select api_calls_used from api_key where id = ?", Long.class, KEY)).isEqualTo(44L);
    }

    @Test
    void aRevokedKeyIsNotCounted() {
        jdbcTemplate.update("update api_key set active = false where id = ?", KEY);

        assertThat(apiKeyRepository.recordRequest(KEY, LocalDateTime.now())).isZero();
        assertThat(jdbcTemplate.queryForMap("select api_calls_used, last_used_at from api_key where id = ?", KEY))
                .containsEntry("API_CALLS_USED", 4L).containsEntry("LAST_USED_AT", null);
    }

    @Test
    void anExpiredKeyIsNotCountedAndAKeyThatNeverExpiresIs() {
        assertThat(apiKeyRepository.recordRequest(KEY, EXPIRES.plusDays(1))).isZero();

        jdbcTemplate.update("update api_key set expiration_date = null where id = ?", KEY);
        assertThat(apiKeyRepository.recordRequest(KEY, EXPIRES.plusYears(10))).isOne();
        assertThat(jdbcTemplate.queryForObject("select api_calls_used from api_key where id = ?", Long.class, KEY)).isEqualTo(5L);
    }

    @Test
    void aKeyOfAnInactiveAccountIsNotCounted() {
        jdbcTemplate.update("update user_account set active = false where id = ?", OWNER);

        assertThat(apiKeyRepository.recordRequest(KEY, LocalDateTime.now())).isZero();
    }

    @Test
    void anUnknownKeyIsNotCounted() {
        assertThat(apiKeyRepository.recordRequest(KEY + 1000, LocalDateTime.now())).isZero();
    }

    @Test
    void theLastUseOfAnAccountIsItsNewestKeyUseRevokedKeysIncluded() {
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, last_used_at, active)
                values (?, ?, 'newest', ?, 1, ?, false)""", KEY + 1, ApiKeyTokens.hash("revoked-but-newest"), OWNER, EXPIRES.minusDays(1));
        jdbcTemplate.update("update api_key set last_used_at = ? where id = ?", EXPIRES.minusDays(9), KEY);

        assertThat(apiKeyRepository.findLastUseByOwnerId(OWNER)).contains(EXPIRES.minusDays(1));
        assertThat(apiKeyRepository.findLastUseByOwnerId(OWNER + 1)).isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, UserRepository.class, ApiKeyRepository.class}))
    @Import({DefaultApiKeyService.class, ClockConfiguration.class})
    static class TestApplication {
    }

}
