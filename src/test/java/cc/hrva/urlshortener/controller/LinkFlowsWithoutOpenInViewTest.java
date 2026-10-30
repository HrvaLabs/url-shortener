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
import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
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
import cc.hrva.urlshortener.service.CampaignService;
import cc.hrva.urlshortener.service.ClickAnalyticsService;
import cc.hrva.urlshortener.service.ClickCheckService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.RedirectChainService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultApiKeyService;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.linksignal.AnonymousLinkSignalsTestConfiguration;
import cc.hrva.urlshortener.service.settings.DefaultSettingsTestConfiguration;
import cc.hrva.urlshortener.validator.impl.DefaultApiKeyValidator;
import cc.hrva.urlshortener.validator.impl.DefaultUrlValidator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
import java.util.Optional;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = LinkFlowsWithoutOpenInViewTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.jpa.open-in-view=false",
                "spring.datasource.url=jdbc:h2:mem:link-flows-without-open-in-view;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost",
                "app.anonymous-url-creation-enabled=true"
        })
@AutoConfigureMockMvc
class LinkFlowsWithoutOpenInViewTest {

    private static final long OWNER = 500L;
    private static final long OTHER = 501L;
    private static final long OWNERS_LINK = 510L;
    private static final long OWNERS_KEY = 520L;
    private static final long OWNERS_DOMAIN = 530L;
    private static final String DOMAIN_REFUSAL = "{\"domain\":\"Choose one of your active domains, or leave it empty for hrva.cc.\"}";
    private static final String OWNER_EMAIL = "owner@hrva.cc";
    private static final String OTHER_EMAIL = "other@hrva.cc";
    private static final String OWNER_TOKEN = "Bearer owner-token";
    private static final String OTHER_TOKEN = "Bearer other-token";
    private static final String OWNERS_KEY_VALUE = "owners-key";
    private static final long ROLE_USER = 1L;
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UrlService urlService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    private CampaignService campaignService;

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
        account(OWNER, OWNER_EMAIL, ROLE_USER);
        account(OTHER, OTHER_EMAIL, ROLE_USER);
        jdbcTemplate.update("insert into url (id, short_url, long_url, owner_id, active, visits) values (?, 'mine2', 'https://example.com/mine', ?, false, 0)",
                OWNERS_LINK, OWNER);
        signedIn("owner-token", OWNER_EMAIL);
        signedIn("other-token", OTHER_EMAIL);
        jdbcTemplate.update("""
                insert into api_key (id, "KEY", owner_id, api_calls_limit, api_calls_used, expiration_date, active)
                values (?, ?, ?, 100, 0, dateadd('MONTH', 6, current_date), true)""", OWNERS_KEY, OWNERS_KEY_VALUE, OWNER);
        when(userService.getUserFromToken()).thenAnswer(invocation -> signedInAccount());
        when(linkCheckService.check(any())).thenReturn(LinkVerdict.clean());
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from url where owner_id in (?, ?)", OWNER, OTHER);
        jdbcTemplate.update("delete from custom_domain where owner_id in (?, ?)", OWNER, OTHER);
        jdbcTemplate.update("delete from api_key where owner_id = ?", OWNER);
        jdbcTemplate.update("delete from user_account_authorities where user_id in (?, ?)", OWNER, OTHER);
        jdbcTemplate.update("delete from user_account where id in (?, ?)", OWNER, OTHER);
    }

    @Test
    void aLinkCreatedWithAnApiKeyBelongsToTheKeysOwner() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .header("X-Api-Key", OWNERS_KEY_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/by-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/by-key"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from url where owner_id = ? and long_url = 'https://example.com/by-key'",
                Long.class, OWNER)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("select api_calls_used from api_key where id = ?", Long.class, OWNERS_KEY)).isEqualTo(1L);
    }

    @Test
    void theOwnerReactivatesTheirOwnLink() throws Exception {
        mockMvc.perform(patch("/api/v1/urls/{id}/activate", OWNERS_LINK).header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(jdbcTemplate.queryForObject("select active from url where id = ?", Boolean.class, OWNERS_LINK)).isTrue();
    }

    @Test
    void theOwnerEditsTheirOwnLink() throws Exception {
        mockMvc.perform(put("/api/v1/urls/{id}", OWNERS_LINK)
                        .header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + OWNERS_LINK + ",\"longUrl\":\"https://example.com/mine\",\"visitLimit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitLimit").value(5));

        assertThat(jdbcTemplate.queryForObject("select visit_limit from url where id = ?", Long.class, OWNERS_LINK)).isEqualTo(5L);
    }

    @Test
    void anotherUserCantReactivateOrEditTheLink() throws Exception {
        mockMvc.perform(patch("/api/v1/urls/{id}/activate", OWNERS_LINK).header(JwtFilter.AUTHORIZATION_HEADER, OTHER_TOKEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/urls/{id}", OWNERS_LINK)
                        .header(JwtFilter.AUTHORIZATION_HEADER, OTHER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + OWNERS_LINK + ",\"longUrl\":\"https://example.com/mine\",\"visitLimit\":5}"))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForMap("select active, visit_limit from url where id = ?", OWNERS_LINK))
                .containsEntry("ACTIVE", false)
                .containsEntry("VISIT_LIMIT", null);
    }

    @Test
    void aDomainLinkSerialisesAfterItsTransaction() throws Exception {
        domain(OWNERS_DOMAIN, OWNER, "go.example.com", "ACTIVE");

        mockMvc.perform(post("/api/v1/urls")
                        .header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/menu\",\"shortUrl\":\"menu\",\"domain\":\"Go.Example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUrl").value("menu"))
                .andExpect(jsonPath("$.domain.id").value(OWNERS_DOMAIN))
                .andExpect(jsonPath("$.domain.hostname").value("go.example.com"));
        final var id = linkOn(OWNERS_DOMAIN, "menu");

        mockMvc.perform(put("/api/v1/urls/{id}", id)
                        .header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + id + ",\"longUrl\":\"https://example.com/menu\",\"visitLimit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain.hostname").value("go.example.com"));
        mockMvc.perform(patch("/api/v1/urls/{id}/deactivate", id).header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain.hostname").value("go.example.com"));
        mockMvc.perform(patch("/api/v1/urls/{id}/activate", id).header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.domain.hostname").value("go.example.com"));
        mockMvc.perform(get("/api/v1/urls").header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.shortUrl == 'menu')].domain.hostname").value("go.example.com"))
                .andExpect(jsonPath("$.content[?(@.shortUrl == 'mine2')].domain").doesNotExist());

        assertThat(jdbcTemplate.queryForMap("select domain_id, visit_limit, active from url where id = ?", id))
                .containsEntry("DOMAIN_ID", OWNERS_DOMAIN)
                .containsEntry("VISIT_LIMIT", 5L)
                .containsEntry("ACTIVE", true);
    }

    @Test
    void theSameCustomAliasOnHrvaAndOnTheDomainIsFineButNotTwiceOnTheDomain() throws Exception {
        domain(OWNERS_DOMAIN, OWNER, "go.example.com", "ACTIVE");

        create(OWNER_TOKEN, "{\"longUrl\":\"https://example.com/a\",\"shortUrl\":\"menu\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").doesNotExist());
        create(OWNER_TOKEN, "{\"longUrl\":\"https://example.com/b\",\"shortUrl\":\"menu\",\"domain\":\"go.example.com\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain.hostname").value("go.example.com"));
        create(OWNER_TOKEN, "{\"longUrl\":\"https://example.com/c\",\"shortUrl\":\"menu\",\"domain\":\"go.example.com\"}")
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"This short URL is already taken. Try another.\"}", JsonCompareMode.STRICT));

        assertThat(jdbcTemplate.queryForList("select long_url from url where short_url = 'menu' order by id", String.class))
                .containsExactly("https://example.com/a", "https://example.com/b");
    }

    @Test
    void someoneElsesOrAPendingDomainIsRefused() throws Exception {
        domain(OWNERS_DOMAIN, OWNER, "go.example.com", "ACTIVE");
        domain(OWNERS_DOMAIN + 1, OWNER, "pending.example.com", "PENDING_REVIEW");

        create(OTHER_TOKEN, "{\"longUrl\":\"https://example.com/a\",\"domain\":\"go.example.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().json(DOMAIN_REFUSAL, JsonCompareMode.STRICT));
        create(OWNER_TOKEN, "{\"longUrl\":\"https://example.com/a\",\"domain\":\"pending.example.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().json(DOMAIN_REFUSAL, JsonCompareMode.STRICT));
        create(OWNER_TOKEN, "{\"longUrl\":\"https://example.com/a\",\"domain\":\"" + "a".repeat(250) + ".com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().json(DOMAIN_REFUSAL, JsonCompareMode.STRICT));

        assertThat(linkCount()).isEqualTo(1L);
    }

    @Test
    void anonymousCannotPickADomain() throws Exception {
        domain(OWNERS_DOMAIN, OWNER, "go.example.com", "ACTIVE");
        doReturn(null).when(userService).getUserFromToken();

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/anonymous\",\"domain\":\"go.example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(DOMAIN_REFUSAL, JsonCompareMode.STRICT));

        assertThat(jdbcTemplate.queryForObject("select count(*) from url where long_url = 'https://example.com/anonymous'", Long.class)).isZero();
    }

    @Test
    void aNewAnonymousLinkWithAHeldShapeIsStoredInactiveAndDoesNotRedirect() throws Exception {
        final var longUrl = "https://ll.omaehhm.com/wqamhcsom/?r=held1&t=1";
        doReturn(null).when(userService).getUserFromToken();
        when(campaignService.holdingCampaignId(any())).thenReturn(Optional.of(77L));
        when(campaignService.confirmHold(77L)).thenReturn(true);
        when(clickCheckService.mayRedirect(anyLong(), any())).thenReturn(true);

        try {
            mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"" + longUrl + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            final var stored = jdbcTemplate.queryForMap("select short_url, active, block_reason, block_detail from url where long_url = ?", longUrl);
            assertThat(stored.get("ACTIVE")).isEqualTo(false);
            assertThat(stored.get("BLOCK_REASON")).isEqualTo("CAMPAIGN");
            assertThat(stored.get("BLOCK_DETAIL")).isEqualTo("/wqamhcsom?r&t");
            final var alias = (String) stored.get("SHORT_URL");
            verify(campaignService).addHeldLink(eq(77L), anyLong(), eq("omaehhm.com"));
            assertThat(locationOf(urlService.redirectResultUrl(alias, ClickRequest.of("203.0.113.7", "UA", null, "HR")))).isNotEqualTo(longUrl);
        } finally {
            jdbcTemplate.update("delete from url where long_url = ?", longUrl);
        }
    }

    @Test
    void aHoldAllowedBeforeTheCreateCommitsLeavesTheLinkActiveAndRedirecting() throws Exception {
        final var longUrl = "https://ll.omaehhm.com/wqamhcsom/?r=allowed1&t=1";
        doReturn(null).when(userService).getUserFromToken();
        when(campaignService.holdingCampaignId(any())).thenReturn(Optional.of(78L));
        when(campaignService.confirmHold(78L)).thenReturn(false);
        when(clickCheckService.mayRedirect(anyLong(), any())).thenReturn(true);

        try {
            mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"" + longUrl + "\"}"))
                    .andExpect(status().isOk());

            final var stored = jdbcTemplate.queryForMap("select short_url, active, block_reason from url where long_url = ?", longUrl);
            assertThat(stored.get("ACTIVE")).isEqualTo(true);
            assertThat(stored.get("BLOCK_REASON")).isNull();
            verify(campaignService, org.mockito.Mockito.never()).addHeldLink(anyLong(), anyLong(), any());
            assertThat(locationOf(urlService.redirectResultUrl((String) stored.get("SHORT_URL"), ClickRequest.of("203.0.113.7", "UA", null, "HR"))))
                    .isEqualTo(longUrl);
        } finally {
            jdbcTemplate.update("delete from url where long_url = ?", longUrl);
        }
    }

    @Test
    void aNewAnonymousLinkWithoutAHeldShapeIsStoredActiveAndRedirects() throws Exception {
        final var longUrl = "https://example.com/anonymous-active?r=1";
        doReturn(null).when(userService).getUserFromToken();
        when(clickCheckService.mayRedirect(anyLong(), any())).thenReturn(true);

        try {
            mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"" + longUrl + "\"}"))
                    .andExpect(status().isOk());

            final var stored = jdbcTemplate.queryForMap("select short_url, active, block_reason from url where long_url = ?", longUrl);
            assertThat(stored.get("ACTIVE")).isEqualTo(true);
            assertThat(stored.get("BLOCK_REASON")).isNull();
            assertThat(locationOf(urlService.redirectResultUrl((String) stored.get("SHORT_URL"), ClickRequest.of("203.0.113.7", "UA", null, "HR"))))
                    .isEqualTo(longUrl);
        } finally {
            jdbcTemplate.update("delete from url where long_url = ?", longUrl);
        }
    }

    @Test
    void aSignedInLinkIsStoredActive() throws Exception {
        create(OWNER_TOKEN, "{\"longUrl\":\"https://example.com/signed-in-active\"}").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("select active from url where long_url = 'https://example.com/signed-in-active'", Boolean.class)).isTrue();
    }

    @Test
    void aDeactivatedOwnerCannotUseTheirDomain() throws Exception {
        domain(OWNERS_DOMAIN, OWNER, "go.example.com", "ACTIVE");
        jdbcTemplate.update("update user_account set active = false where id = ?", OWNER);

        create(OWNER_TOKEN, "{\"longUrl\":\"https://example.com/a\",\"domain\":\"go.example.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().json(DOMAIN_REFUSAL, JsonCompareMode.STRICT));

        assertThat(linkCount()).isEqualTo(1L);
    }

    @Test
    void apiKeyCreatesUseTheKeyOwnersDomains() throws Exception {
        domain(OWNERS_DOMAIN, OWNER, "go.example.com", "ACTIVE");
        domain(OWNERS_DOMAIN + 1, OTHER, "other.example.com", "ACTIVE");

        mockMvc.perform(post("/api/v1/urls")
                        .header("X-Api-Key", OWNERS_KEY_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/by-key\",\"domain\":\"go.example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain.hostname").value("go.example.com"));
        mockMvc.perform(post("/api/v1/urls")
                        .header("X-Api-Key", OWNERS_KEY_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/by-key-2\",\"domain\":\"other.example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(DOMAIN_REFUSAL, JsonCompareMode.STRICT));

        assertThat(jdbcTemplate.queryForObject("select count(*) from url where owner_id = ? and domain_id = ?", Long.class, OWNER, OWNERS_DOMAIN))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("select api_calls_used from api_key where id = ?", Long.class, OWNERS_KEY)).isEqualTo(1L);
    }

    @Test
    void bulkChecksEachItemsDomain() throws Exception {
        domain(OWNERS_DOMAIN, OWNER, "go.example.com", "ACTIVE");

        mockMvc.perform(post("/api/v1/urls/bulk")
                        .header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"longUrl\":\"https://example.com/1\"},{\"longUrl\":\"https://example.com/2\",\"domain\":\"go.example.com\"},"
                                + "{\"longUrl\":\"https://example.com/3\",\"domain\":\"elsewhere.example.com\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(DOMAIN_REFUSAL, JsonCompareMode.STRICT));

        assertThat(linkCount()).isEqualTo(1L);

        mockMvc.perform(post("/api/v1/urls/bulk")
                        .header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"longUrl\":\"https://example.com/1\"},{\"longUrl\":\"https://example.com/2\",\"domain\":\"go.example.com\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].domain").doesNotExist())
                .andExpect(jsonPath("$[1].domain.hostname").value("go.example.com"));
    }

    @Test
    void aLinkOnARemovedDomainCannotBeReactivated() throws Exception {
        domain(OWNERS_DOMAIN, OWNER, "go.example.com", "DISABLED");
        jdbcTemplate.update("update url set domain_id = ? where id = ?", OWNERS_DOMAIN, OWNERS_LINK);

        mockMvc.perform(patch("/api/v1/urls/{id}/activate", OWNERS_LINK).header(JwtFilter.AUTHORIZATION_HEADER, OWNER_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"This link's domain isn't active, so the link can't be turned on.\"}", JsonCompareMode.STRICT));

        assertThat(jdbcTemplate.queryForObject("select active from url where id = ?", Boolean.class, OWNERS_LINK)).isFalse();
    }

    private ResultActions create(final String token, final String body) throws Exception {
        return mockMvc.perform(post("/api/v1/urls")
                .header(JwtFilter.AUTHORIZATION_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void domain(final long id, final long owner, final String hostname, final String status) {
        jdbcTemplate.update("""
                insert into custom_domain (id, owner_id, hostname, hostname_key, status, reason, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'Menu links for my cafe', current_timestamp, current_timestamp)""", id, owner, hostname, hostname, status);
    }

    private long linkOn(final long domainId, final String alias) {
        return jdbcTemplate.queryForObject("select id from url where domain_id = ? and short_url = ?", Long.class, domainId, alias);
    }

    private long linkCount() {
        return jdbcTemplate.queryForObject("select count(*) from url where owner_id in (?, ?)", Long.class, OWNER, OTHER);
    }

    private void account(final long id, final String email, final long... roles) {
        jdbcTemplate.update("insert into user_account (id, email, password, active) values (?, ?, 'secret-hash', true)", id, email);
        for (final var role : roles) {
            jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", id, role);
        }
    }

    private void signedIn(final String token, final String email) {
        when(tokenProvider.validateToken(token)).thenReturn(true);
        when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private User signedInAccount() {
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> userRepository.findByEmail(SecurityContextHolder.getContext().getAuthentication().getName()).orElse(null));
    }

    private static String locationOf(final View view) {
        assertThat(view).isInstanceOf(RedirectView.class);
        return ((RedirectView) view).getUrl();
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
            DefaultSettingsTestConfiguration.class,
            AnonymousLinkSignalsTestConfiguration.class,
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
