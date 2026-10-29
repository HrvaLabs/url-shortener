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
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
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
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.impl.DefaultApiKeyService;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.validator.impl.DefaultApiKeyValidator;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class LinkFlowsWithoutOpenInViewTest {

    private static final long OWNER = 500L;
    private static final long OTHER = 501L;
    private static final long OWNERS_LINK = 510L;
    private static final long OWNERS_KEY = 520L;
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
    private QrCodeService qrCodeService;

    @MockitoBean
    private LinkPreviewService linkPreviewService;

    @MockitoBean
    private CacheManager cacheManager;

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
        jdbcTemplate.update("delete from url where owner_id = ?", OWNER);
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
                    classes = {UrlRepository.class, UserRepository.class, ApiKeyRepository.class, AuditLogRepository.class}))
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
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
