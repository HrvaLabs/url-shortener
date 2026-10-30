package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.AdminAuditAspect;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.converter.CreateUrlToUrlConverter;
import cc.hrva.urlshortener.converter.UrlToPeekUrlConverter;
import cc.hrva.urlshortener.converter.UrlUpdateDtoToUrlConverter;
import cc.hrva.urlshortener.dto.ReputationResult;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
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
import cc.hrva.urlshortener.service.ApiKeyService;
import cc.hrva.urlshortener.service.BurstAlertService;
import cc.hrva.urlshortener.service.CampaignService;
import cc.hrva.urlshortener.service.ClickAnalyticsService;
import cc.hrva.urlshortener.service.ClickCheckService;
import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.service.DomainReputationService;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.RedirectChainService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultLinkCheckService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.linksignal.AnonymousLinkSignalsTestConfiguration;
import cc.hrva.urlshortener.service.settings.DefaultSettingsTestConfiguration;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import cc.hrva.urlshortener.validator.impl.DefaultUrlValidator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = UpdateLinkFlowTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:update-link-flow;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class UpdateLinkFlowTest {

    private static final long OWNER = 400L;
    private static final long ADMIN = 401L;
    private static final long LINK = 410L;
    private static final String OWNER_EMAIL = "owner@hrva.cc";
    private static final String ADMIN_EMAIL = "admin@hrva.cc";
    private static final long ROLE_USER = 1L;
    private static final long ROLE_ADMIN = 2L;
    private static final String OLD_DESTINATION = "https://example.com/old";
    private static final String OLD_FINAL_URL = "https://old.example/landing";
    private static final String BLOCKED_WHILE_EDITING = "This link was blocked while you were editing it. Reload and try again.";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    private BlocklistStore blocklistStore;

    @MockitoBean
    private DomainAgeService domainAgeService;

    @MockitoBean
    private DomainReputationService domainReputationService;

    @MockitoBean
    private SafeBrowsingService safeBrowsingService;

    @MockitoBean
    private SuspiciousUrlService suspiciousUrlService;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private ApiKeyValidator apiKeyValidator;

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
        account(ADMIN, ADMIN_EMAIL, ROLE_USER, ROLE_ADMIN);
        jdbcTemplate.update("""
                insert into url (id, short_url, long_url, owner_id, active, visits, final_url, chain_checked_at)
                values (?, 'edit1', ?, ?, true, 0, ?, current_timestamp)""",
                LINK, OLD_DESTINATION, OWNER, OLD_FINAL_URL);
        signedIn("owner-token", OWNER_EMAIL, "ROLE_USER");
        signedIn("admin-token", ADMIN_EMAIL, "ROLE_ADMIN");
        when(userService.getUserFromToken()).thenAnswer(invocation -> signedInAccount());
        when(domainReputationService.lookup(anyString())).thenReturn(ReputationResult.none());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from url_tags where url_id = ?", LINK);
        jdbcTemplate.update("delete from url where id = ?", LINK);
        jdbcTemplate.update("delete from user_account_authorities where user_id in (?, ?)", OWNER, ADMIN);
        jdbcTemplate.update("delete from user_account where id in (?, ?)", OWNER, ADMIN);
    }

    @Test
    void anAdminsNewDestinationStartsWithoutTheOldDestinationsRedirectDetails() throws Exception {
        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.org/new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.org/new"));

        assertThat(jdbcTemplate.queryForMap("select long_url, final_url, chain_checked_at from url where id = ?", LINK))
                .containsEntry("LONG_URL", "https://example.org/new")
                .containsEntry("FINAL_URL", null)
                .containsEntry("CHAIN_CHECKED_AT", null);
    }

    @Test
    void aBlockMadeRightAfterAnAdminsNewDestinationIsSavedIsKept() throws Exception {
        doAnswer(invocation -> {
            blockMeanwhile();
            return null;
        }).when(redirectChainService).checkLater(LINK);

        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.org/new\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForMap("select long_url, final_url, active, block_reason, block_detail from url where id = ?", LINK))
                .containsEntry("LONG_URL", "https://example.org/new")
                .containsEntry("FINAL_URL", null)
                .containsEntry("ACTIVE", false)
                .containsEntry("BLOCK_REASON", "ADMIN")
                .containsEntry("BLOCK_DETAIL", "blocked meanwhile");
    }

    @Test
    void aBlockMadeWhileAnAdminsNewDestinationIsCheckedRefusesTheEditAndIsKept() throws Exception {
        when(safeBrowsingService.checkUrlsForThreats(anyString())).thenAnswer(invocation -> {
            blockMeanwhile();
            return List.of();
        });

        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.org/new\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(BLOCKED_WHILE_EDITING));

        assertThat(jdbcTemplate.queryForMap("select long_url, active, block_reason, block_detail from url where id = ?", LINK))
                .containsEntry("LONG_URL", OLD_DESTINATION)
                .containsEntry("ACTIVE", false)
                .containsEntry("BLOCK_REASON", "ADMIN")
                .containsEntry("BLOCK_DETAIL", "blocked meanwhile");
    }

    @Test
    void anOwnerWhoIsRefusedANewDestinationKeepsTheOldOne() throws Exception {
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.net/elsewhere\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only admins can change where a link points"));

        assertThat(jdbcTemplate.queryForObject("select long_url from url where id = ?", String.class, LINK)).isEqualTo(OLD_DESTINATION);
    }

    @Test
    void aRefusedExpirationDateIsNotSaved() throws Exception {
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"expirationDate\":\"2020-01-01T00:00:00\",\"visitLimit\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Expiration date can't be in the past"));

        assertThat(jdbcTemplate.queryForMap("select expiration_date, visit_limit from url where id = ?", LINK))
                .containsEntry("EXPIRATION_DATE", null)
                .containsEntry("VISIT_LIMIT", null);
    }

    @Test
    void aBlockMadeWhileAnOwnersEditIsCheckedRefusesTheEditAndIsKept() throws Exception {
        doAnswer(invocation -> signedInAccount())
                .doAnswer(invocation -> {
                    blockMeanwhile();
                    return signedInAccount();
                })
                .doAnswer(invocation -> signedInAccount())
                .when(userService).getUserFromToken();

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"visitLimit\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(BLOCKED_WHILE_EDITING));

        assertThat(jdbcTemplate.queryForMap("select visit_limit, active, block_reason, block_detail from url where id = ?", LINK))
                .containsEntry("VISIT_LIMIT", null)
                .containsEntry("ACTIVE", false)
                .containsEntry("BLOCK_REASON", "ADMIN")
                .containsEntry("BLOCK_DETAIL", "blocked meanwhile");
    }

    @Test
    void anAdminsDestinationRefusedByTheBlocklistLeavesTheLinkAsItWas() throws Exception {
        final var before = jdbcTemplate.queryForMap("select * from url where id = ?", LINK);

        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://njuskaio.cam/a/MAUB3\",\"visitLimit\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This destination is on a blocklist and can't be shortened. If you think that's a mistake, contact us."));

        assertThat(jdbcTemplate.queryForMap("select * from url where id = ?", LINK)).isEqualTo(before);
    }

    @Test
    void anAdminsDestinationRefusedAsAShortenerLeavesTheLinkAsItWas() throws Exception {
        when(suspiciousUrlService.isShortener("https://bit.ly/abc")).thenReturn(true);
        final var before = jdbcTemplate.queryForMap("select * from url where id = ?", LINK);

        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://bit.ly/abc\",\"visitLimit\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Links to other URL shorteners can't be shortened. Use the final destination instead."));

        assertThat(jdbcTemplate.queryForMap("select * from url where id = ?", LINK)).isEqualTo(before);
    }

    @Test
    void anOwnersEditIsSaved() throws Exception {
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"visitLimit\":5,\"tags\":[\"work\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitLimit").value(5));

        assertThat(jdbcTemplate.queryForMap("select long_url, visit_limit, final_url from url where id = ?", LINK))
                .containsEntry("LONG_URL", OLD_DESTINATION)
                .containsEntry("VISIT_LIMIT", 5L)
                .containsEntry("FINAL_URL", OLD_FINAL_URL);
        assertThat(jdbcTemplate.queryForList("select tag from url_tags where url_id = ?", String.class, LINK)).containsExactly("work");
    }

    private MockHttpServletRequestBuilder edit(final String token, final String body) {
        return put("/api/v1/urls/{id}", LINK)
                .header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void blockMeanwhile() {
        jdbcTemplate.update("update url set active = false, block_reason = 'ADMIN', block_detail = 'blocked meanwhile', blocked_at = current_timestamp where id = ?",
                LINK);
    }

    private void account(final long id, final String email, final long... roles) {
        jdbcTemplate.update("insert into user_account (id, email, password) values (?, ?, 'secret-hash')", id, email);
        for (final var role : roles) {
            jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", id, role);
        }
    }

    private void signedIn(final String token, final String email, final String role) {
        when(tokenProvider.validateToken(token)).thenReturn(true);
        when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
    }

    private User signedInAccount() {
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> userRepository.findByEmail(SecurityContextHolder.getContext().getAuthentication().getName()).orElse(null));
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
                    classes = {UrlRepository.class, CustomDomainRepository.class, UserRepository.class, AuditLogRepository.class, DomainRuleRepository.class,
                            BlocklistSourceRepository.class}))
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
            DefaultLinkCheckService.class,
            CreateUrlToUrlConverter.class,
            UrlToPeekUrlConverter.class,
            UrlUpdateDtoToUrlConverter.class,
            AdminAuditAspect.class,
            DefaultAuditLogService.class
    })
    static class TestApplication {
    }

}
