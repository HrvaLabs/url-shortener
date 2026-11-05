package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.audit.AdminAuditAspect;
import cc.hrva.urlshortener.audit.AuditSubjects;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.ClockConfiguration;
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
import cc.hrva.urlshortener.repository.CustomDomainRequestRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ApiKeyBearerFilter;
import cc.hrva.urlshortener.security.ApiKeyRateLimitFilter;
import cc.hrva.urlshortener.security.ApiKeyTokens;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.BurstAlertService;
import cc.hrva.urlshortener.service.DestinationChangeService;
import cc.hrva.urlshortener.service.CampaignService;
import cc.hrva.urlshortener.service.ClickAnalyticsService;
import cc.hrva.urlshortener.service.ClickCheckService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.RedirectChainService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultApiKeyService;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.service.inspection.LinkInspectionService;
import cc.hrva.urlshortener.service.ratelimit.RateLimitWindow;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.HumanCheckTestConfiguration;
import cc.hrva.urlshortener.service.SignInFormCheckTestConfiguration;
import cc.hrva.urlshortener.service.linksignal.AnonymousLinkSignalsTestConfiguration;
import cc.hrva.urlshortener.service.settings.DefaultSettingsTestConfiguration;
import cc.hrva.urlshortener.validator.impl.DefaultApiKeyValidator;
import cc.hrva.urlshortener.validator.impl.DefaultUrlValidator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    private static final long BEARER_KEY = 541L;
    private static final String BEARER_KEY_VALUE = "hrva_" + "B".repeat(43);
    private static final long ROLE_USER = 1L;
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    private Runnable duringChecks = () -> {};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LinkInspectionService linkInspectionService;

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
    private DestinationChangeService destinationChangeService;

    @MockitoBean
    private CampaignService campaignService;

    @MockitoBean
    private QrCodeService qrCodeService;

    @MockitoBean
    private LinkPreviewService linkPreviewService;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private CustomDomainRegistry customDomainRegistry;

    @MockitoBean
    private TaskScheduler taskScheduler;

    @MockitoBean
    private ModerationService moderationService;

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
        when(rateLimiter.incrementWindow(anyString(), any())).thenReturn(new RateLimitWindow(1, Duration.ofHours(1)));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from url where owner_id = ?", OWNER);
        jdbcTemplate.update("delete from api_key where owner_id = ?", OWNER);
        jdbcTemplate.update("delete from user_account_authorities where user_id = ?", OWNER);
        jdbcTemplate.update("delete from user_account where id = ?", OWNER);
    }

    @Test
    void aKeyRevokedWhileTheLinkIsCheckedStaysRevokedAndTheLinkIsRefusedThoughTheAuthenticatedRequestCounted() throws Exception {
        key(0, LocalDateTime.now().plusMonths(6));
        duringChecks = () -> meanwhile("update api_key set active = false where id = ?", OWNERS_KEY);

        create("https://example.com/revoked-meanwhile")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("API key is invalid"));

        assertThat(keyRow()).containsEntry("ACTIVE", false).containsEntry("API_CALLS_USED", 1L);
        assertThat(linksOfTheOwner()).isZero();
    }

    @Test
    void twoCreatesWithOneKeyAtTheSameTimeBothCount() throws Exception {
        key(0, LocalDateTime.now().plusMonths(6));
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

        assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))).containsExactly(201, 201);
        assertThat(keyRow()).containsEntry("API_CALLS_USED", 2L).containsEntry("ACTIVE", true);
        assertThat(linksOfTheOwner()).isEqualTo(2L);
    }

    @Test
    void aKeyThatExpiresWhileTheLinkIsCheckedStillMakesTheLinkItsRequestWasAuthenticatedFor() throws Exception {
        key(0, LocalDateTime.now().plusMonths(6));
        duringChecks = () -> meanwhile("update api_key set expiration_date = ? where id = ?", LocalDateTime.now().minusMinutes(1), OWNERS_KEY);

        create("https://example.com/expired-meanwhile").andExpect(status().isCreated());

        assertThat(keyRow()).containsEntry("ACTIVE", true).containsEntry("API_CALLS_USED", 1L);
        assertThat(linksOfTheOwner()).isEqualTo(1L);
    }

    @Test
    void aKeyKeepsWorkingWhateverItsTotalAndEachCreateCountsOnce() throws Exception {
        key(256, LocalDateTime.now().plusMonths(6));

        create("https://example.com/past-the-old-cap").andExpect(status().isCreated());
        create("https://example.com/and-again").andExpect(status().isCreated());

        assertThat(keyRow()).containsEntry("API_CALLS_USED", 258L).containsEntry("ACTIVE", true);
        assertThat(jdbcTemplate.queryForObject("select last_used_at from api_key where id = ?", LocalDateTime.class, OWNERS_KEY))
                .isCloseTo(LocalDateTime.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void aKeyThatNeverExpiresWorks() throws Exception {
        key(7, null);

        create("https://example.com/never-expires").andExpect(status().isCreated());

        assertThat(keyRow()).containsEntry("API_CALLS_USED", 8L).containsEntry("ACTIVE", true);
    }

    @Test
    void anApiKeyResponseCarriesItsRateLimitHeadersAndExposesThemToTheFrontend() throws Exception {
        key(0, LocalDateTime.now().plusMonths(6));
        when(rateLimiter.incrementWindow("api-key:" + OWNERS_KEY, Duration.ofHours(1))).thenReturn(new RateLimitWindow(3, Duration.ofSeconds(1_200)));

        mockMvc.perform(post("/api/v1/urls")
                        .header("X-Api-Key", OWNERS_KEY_VALUE)
                        .header("Origin", "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/with-headers\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("RateLimit-Limit", "600"))
                .andExpect(header().string("RateLimit-Remaining", "597"))
                .andExpect(header().string("RateLimit-Reset", "1200"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("RateLimit-Limit"),
                                org.hamcrest.Matchers.containsString("RateLimit-Remaining"), org.hamcrest.Matchers.containsString("RateLimit-Reset"),
                                org.hamcrest.Matchers.containsString("Retry-After"), org.hamcrest.Matchers.containsString("Location"))));
    }

    @Test
    void aKeySentAsABearerTokenCreatesTheLinkAndCountsLikeXApiKey() throws Exception {
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, expiration_date, active)
                values (?, ?, 'hrva_B', ?, 0, ?, true)""", BEARER_KEY, ApiKeyTokens.hash(BEARER_KEY_VALUE), OWNER, LocalDateTime.now().plusMonths(6));

        final var created = mockMvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + BEARER_KEY_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/bearer\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("RateLimit-Limit", "600"))
                .andReturn().getResponse();

        assertThat(jdbcTemplate.queryForObject("select api_calls_used from api_key where id = ?", Long.class, BEARER_KEY)).isOne();
        assertThat(linksOfTheOwner()).isOne();
        verifyNoInteractions(tokenProvider);
        assertThat(created.getHeader("Location")).endsWith("/peek");
    }

    @Test
    void underDenyByDefaultAKeySentAsABearerTokenPassesThePublicGetsAndNeverSignsAnyoneIn() throws Exception {
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, expiration_date, active)
                values (?, ?, 'hrva_B', ?, 0, ?, true)""", BEARER_KEY, ApiKeyTokens.hash(BEARER_KEY_VALUE), OWNER, LocalDateTime.now().plusMonths(6));

        mockMvc.perform(get("/api/v1/urls/{short}/peek", "nothing").header("Authorization", "Bearer " + BEARER_KEY_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("URL doesn't exist"))
                .andExpect(header().string("RateLimit-Limit", "600"));
        mockMvc.perform(get("/api/v1/urls").header("Authorization", "Bearer " + BEARER_KEY_VALUE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Sign in to continue."));

        assertThat(jdbcTemplate.queryForObject("select api_calls_used from api_key where id = ?", Long.class, BEARER_KEY)).isOne();
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void aJwtBearerTokenIsStillReadAsASessionAndNeverAsAKey() throws Exception {
        mockMvc.perform(get("/api/v1/urls/{short}/peek", "nothing").header("Authorization", "Bearer eyJhbGciOiJIUzUxMiJ9.e30.c2ln"))
                .andExpect(header().doesNotExist("RateLimit-Limit"));

        verify(tokenProvider).read("eyJhbGciOiJIUzUxMiJ9.e30.c2ln");
        verify(rateLimiter, never()).incrementWindow(anyString(), any());
    }

    @Test
    void aKeyOverItsHourlyLimitIsRefusedBeforeAnythingIsCreatedOrCounted() throws Exception {
        key(5, LocalDateTime.now().plusMonths(6));
        when(rateLimiter.incrementWindow("api-key:" + OWNERS_KEY, Duration.ofHours(1))).thenReturn(new RateLimitWindow(601, Duration.ofSeconds(90)));

        mockMvc.perform(post("/api/v1/urls")
                        .header("X-Api-Key", OWNERS_KEY_VALUE)
                        .header("Origin", "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/too-many\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Too Many Requests","status":429,
                         "detail":"This API key made too many requests. Try again in 2 minutes.","instance":"/api/v1/urls"}""", JsonCompareMode.STRICT))
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(header().string("RateLimit-Remaining", "0"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));

        assertThat(keyRow()).containsEntry("API_CALLS_USED", 5L);
        assertThat(linksOfTheOwner()).isZero();
    }

    @Test
    void aRequestWithAKeyCountsOnceWhateverTheEndpoint() throws Exception {
        key(0, LocalDateTime.now().plusMonths(6));

        mockMvc.perform(get("/api/v1/urls/{short}/peek", "nothing").header("X-Api-Key", OWNERS_KEY_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(header().string("RateLimit-Limit", "600"));

        org.mockito.Mockito.verify(rateLimiter).incrementWindow("api-key:" + OWNERS_KEY, Duration.ofHours(1));
        assertThat(keyRow()).containsEntry("API_CALLS_USED", 1L);
        assertThat(jdbcTemplate.queryForObject("select last_used_at from api_key where id = ?", LocalDateTime.class, OWNERS_KEY))
                .isCloseTo(LocalDateTime.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void aRequestWithARevokedKeyIsNotCounted() throws Exception {
        key(3, LocalDateTime.now().plusMonths(6));
        jdbcTemplate.update("update api_key set active = false where id = ?", OWNERS_KEY);

        create("https://example.com/with-a-revoked-key")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("API key is invalid"));

        assertThat(keyRow()).containsEntry("API_CALLS_USED", 3L);
        assertThat(jdbcTemplate.queryForObject("select last_used_at from api_key where id = ?", LocalDateTime.class, OWNERS_KEY)).isNull();
    }

    @Test
    void aRequestWithAnExpiredKeyIsNotCounted() throws Exception {
        key(3, LocalDateTime.now().minusDays(2));

        create("https://example.com/with-an-expired-key").andExpect(status().isBadRequest());

        assertThat(keyRow()).containsEntry("API_CALLS_USED", 3L);
    }

    private void key(final long used, final LocalDateTime expires) {
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, expiration_date, active)
                values (?, ?, 'keyhol', ?, ?, ?, true)""", OWNERS_KEY, ApiKeyTokens.hash(OWNERS_KEY_VALUE), OWNER, used, expires);
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
                    classes = {UrlRepository.class, CustomDomainRepository.class, CustomDomainRequestRepository.class, UserRepository.class, ApiKeyRepository.class, AuditLogRepository.class}))
    @Import({
            DefaultSettingsTestConfiguration.class,
            AnonymousLinkSignalsTestConfiguration.class, HumanCheckTestConfiguration.class, SignInFormCheckTestConfiguration.class,
            AppConfiguration.class,
            WebConfiguration.class,
            AnyHostIsPrimary.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ApiKeyBearerFilter.class,
            ApiKeyRateLimitFilter.class,
            ClockConfiguration.class,
            ClientIpResolver.class,
            ClickRequestResolver.class,
            UrlController.class,
            ShortLinks.class,
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
            AuditSubjects.class,
            DefaultAuditLogService.class
    })
    static class TestApplication {
    }

}
