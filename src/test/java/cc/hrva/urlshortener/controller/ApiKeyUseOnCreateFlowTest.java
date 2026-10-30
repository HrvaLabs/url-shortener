package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.AdminAuditAspect;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.converter.ApiKeyUpdateDtoToApiKeyConverter;
import cc.hrva.urlshortener.converter.CreateUrlToUrlConverter;
import cc.hrva.urlshortener.converter.UrlToPeekUrlConverter;
import cc.hrva.urlshortener.converter.UrlUpdateDtoToUrlConverter;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.BurstAlertService;
import cc.hrva.urlshortener.service.ClickAnalyticsService;
import cc.hrva.urlshortener.service.ClickCheckService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.RedirectChainService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.SameShapeAlertService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultApiKeyService;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.validator.impl.DefaultApiKeyValidator;
import cc.hrva.urlshortener.validator.impl.DefaultUrlValidator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ApiKeyUseOnCreateFlowTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                                "spring.datasource.url=jdbc:h2:mem:api-key-use-on-create-flow;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class ApiKeyUseOnCreateFlowTest {

    private static final long OWNER = 530L;
    private static final long OWNERS_KEY = 540L;
    private static final String OWNER_EMAIL = "keyholder@hrva.cc";
    private static final String OWNERS_KEY_VALUE = "keyholders-key";
    private static final long ROLE_USER = 1L;
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    private Runnable duringChecks = () -> {};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private LinkCheckService linkCheckService;

    @MockitoBean
    private SafeBrowsingService safeBrowsingService;

    @MockitoBean
    private SuspiciousUrlService suspiciousUrlService;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    @MockitoBean
    private ClickAnalyticsService clickAnalyticsService;

    @MockitoBean
    private ClickCheckService clickCheckService;

    @MockitoBean
    private ReviewQueueService reviewQueueService;

    @MockitoBean
    private ScammerSignalService scammerSignalService;

    @MockitoBean
    private RedirectChainService redirectChainService;

    @MockitoBean
    private BurstAlertService burstAlertService;

    @MockitoBean
    private SameShapeAlertService sameShapeAlertService;

    @MockitoBean
    private QrCodeService qrCodeService;

    @MockitoBean
    private LinkPreviewService linkPreviewService;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private CustomDomainRegistry customDomainRegistry;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("insert into user_account (id, email, password, active) values (?, ?, 'secret-hash', true)", OWNER, OWNER_EMAIL);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", OWNER, ROLE_USER);
        when(linkCheckService.check(any())).thenAnswer(invocation -> {
            duringChecks.run();
            return LinkVerdict.clean();
        });
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from url where owner_id = ?", OWNER);
        jdbcTemplate.update("delete from api_key where owner_id = ?", OWNER);
        jdbcTemplate.update("delete from user_account_authorities where user_id = ?", OWNER);
        jdbcTemplate.update("delete from user_account where id = ?", OWNER);
    }

    @Test
    void aKeyRevokedWhileTheLinkIsCheckedStaysRevokedAndTheLinkIsRefused() throws Exception {
        key(100, 0, LocalDateTime.now().plusMonths(6));
        duringChecks = () -> meanwhile("update api_key set active = false where id = ?", OWNERS_KEY);

        create("https://example.com/revoked-meanwhile")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("API key is invalid"));

        assertThat(keyRow()).containsEntry("ACTIVE", false).containsEntry("API_CALLS_USED", 0L);
        assertThat(linksOfTheOwner()).isZero();
    }

    @Test
    void twoCreatesWithOneKeyAtTheSameTimeBothCount() throws Exception {
        key(100, 0, LocalDateTime.now().plusMonths(6));
        final var bothChecking = new CyclicBarrier(2);
        duringChecks = () -> {
            try {
                bothChecking.await(10, TimeUnit.SECONDS);
            } catch (final Exception exception) {
                throw new IllegalStateException(exception);
            }
        };

        final var first = CompletableFuture.supplyAsync(() -> createdStatus("https://example.com/first"));
        final var second = CompletableFuture.supplyAsync(() -> createdStatus("https://example.com/second"));

        assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))).containsExactly(200, 200);
        assertThat(keyRow()).containsEntry("API_CALLS_USED", 2L).containsEntry("ACTIVE", true);
        assertThat(linksOfTheOwner()).isEqualTo(2L);
    }

    @Test
    void twoCreatesAtTheSameTimeForTheLastCallOfAKeyLetOnlyOneThrough() throws Exception {
        key(2, 1, LocalDateTime.now().plusMonths(6));
        final var bothChecking = new CyclicBarrier(2);
        duringChecks = () -> {
            try {
                bothChecking.await(10, TimeUnit.SECONDS);
            } catch (final Exception exception) {
                throw new IllegalStateException(exception);
            }
        };

        final var first = CompletableFuture.supplyAsync(() -> createdAnswer("https://example.com/last-call-first"));
        final var second = CompletableFuture.supplyAsync(() -> createdAnswer("https://example.com/last-call-second"));

        final var answers = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 400);
        assertThat(answers).filteredOn(answer -> answer.getStatus() == 400).singleElement()
                .satisfies(refused -> assertThat(refused.getContentAsString()).contains("API key is invalid"));
        assertThat(keyRow()).containsEntry("API_CALLS_USED", 2L).containsEntry("ACTIVE", false);
        assertThat(linksOfTheOwner()).isOne();
    }

    @Test
    void theKeyTurnsOffAtExactlyItsLimit() throws Exception {
        key(3, 1, LocalDateTime.now().plusMonths(6));

        create("https://example.com/second-call").andExpect(status().isOk());
        assertThat(keyRow()).containsEntry("API_CALLS_USED", 2L).containsEntry("ACTIVE", true);

        create("https://example.com/third-call").andExpect(status().isOk());
        assertThat(keyRow()).containsEntry("API_CALLS_USED", 3L).containsEntry("ACTIVE", false);

        create("https://example.com/fourth-call")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("API key is invalid"));
        assertThat(keyRow()).containsEntry("API_CALLS_USED", 3L);
        assertThat(linksOfTheOwner()).isEqualTo(2L);
    }

    @Test
    void aKeyWithoutALimitNeverTurnsOffForItsCalls() throws Exception {
        jdbcTemplate.update("""
                insert into api_key (id, "KEY", owner_id, api_calls_limit, api_calls_used, expiration_date, active)
                values (?, ?, ?, null, 7, null, true)""", OWNERS_KEY, OWNERS_KEY_VALUE, OWNER);

        create("https://example.com/unlimited").andExpect(status().isOk());

        assertThat(keyRow()).containsEntry("API_CALLS_USED", 8L).containsEntry("ACTIVE", true);
    }

    @Test
    void aKeyThatExpiresWhileTheLinkIsCheckedCountsTheLinkAndTurnsOff() throws Exception {
        key(100, 0, LocalDateTime.now().plusMonths(6));
        duringChecks = () -> meanwhile("update api_key set expiration_date = ? where id = ?", LocalDateTime.now().minusMinutes(1), OWNERS_KEY);

        create("https://example.com/expired-meanwhile").andExpect(status().isOk());

        assertThat(keyRow()).containsEntry("ACTIVE", false).containsEntry("API_CALLS_USED", 1L);
        assertThat(linksOfTheOwner()).isEqualTo(1L);
    }

    private void key(final long limit, final long used, final LocalDateTime expires) {
        jdbcTemplate.update("""
                insert into api_key (id, "KEY", owner_id, api_calls_limit, api_calls_used, expiration_date, active)
                values (?, ?, ?, ?, ?, ?, true)""", OWNERS_KEY, OWNERS_KEY_VALUE, OWNER, limit, used, expires);
    }

    private void meanwhile(final String sql, final Object... arguments) {
        CompletableFuture.runAsync(() -> jdbcTemplate.update(sql, arguments)).join();
    }

    private ResultActions create(final String longUrl) throws Exception {
        return mockMvc.perform(post("/api/v1/urls")
                .header("X-Api-Key", OWNERS_KEY_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"longUrl\":\"" + longUrl + "\"}"));
    }

    private int createdStatus(final String longUrl) {
        return createdAnswer(longUrl).getStatus();
    }

    private MockHttpServletResponse createdAnswer(final String longUrl) {
        try {
            return create(longUrl).andReturn().getResponse();
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> keyRow() {
        return jdbcTemplate.queryForMap("select active, api_calls_used from api_key where id = ?", OWNERS_KEY);
    }

    private Long linksOfTheOwner() {
        return jdbcTemplate.queryForObject("select count(*) from url where owner_id = ?", Long.class, OWNER);
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (final JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, CustomDomainRepository.class, UserRepository.class, ApiKeyRepository.class, AuditLogRepository.class}))
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
            AnyHostIsPrimary.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            ClickRequestResolver.class,
            UrlController.class,
            DefaultUrlService.class,
            UrlCache.class,
            DestinationCheckRepository.class,
            DefaultUrlValidator.class,
            DefaultApiKeyService.class,
            DefaultApiKeyValidator.class,
            CreateUrlToUrlConverter.class,
            UrlToPeekUrlConverter.class,
            UrlUpdateDtoToUrlConverter.class,
            ApiKeyUpdateDtoToApiKeyConverter.class,
            AdminAuditAspect.class,
            DefaultAuditLogService.class
    })
    static class TestApplication {
    }

}
