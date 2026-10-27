package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.ApiKeyUpdateDtoToApiKeyConverter;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
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
                insert into api_key (id, "KEY", owner_id, api_calls_limit, api_calls_used, expiration_date, active)
                values (?, 'revoked-while-used', ?, 100, 4, ?, true)""", KEY, OWNER, EXPIRES);
        final var useCommits = new TransactionTemplate(transactionManager);
        useCommits.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            final var loaded = mockingDetails(apiKeyRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            if (concurrentUseDone.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> useCommits.executeWithoutResult(
                        status -> assertThat(apiKeyRepository.recordUse(KEY, LocalDateTime.now())).isOne())).join();
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
        assertThat(jdbcTemplate.queryForMap("select active, api_calls_used, api_calls_limit from api_key where id = ?", KEY))
                .containsEntry("ACTIVE", false)
                .containsEntry("API_CALLS_USED", 5L)
                .containsEntry("API_CALLS_LIMIT", 100L);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, UserRepository.class, ApiKeyRepository.class}))
    @Import(DefaultApiKeyService.class)
    static class TestApplication {
    }

}
