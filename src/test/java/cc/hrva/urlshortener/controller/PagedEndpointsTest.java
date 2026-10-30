package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.AdminBioPageService;
import cc.hrva.urlshortener.service.AdminService;
import cc.hrva.urlshortener.service.ApiKeyService;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.BioPageReviewService;
import cc.hrva.urlshortener.service.CustomDomainAdminService;
import cc.hrva.urlshortener.service.EmailLogService;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = PagedEndpointsTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class PagedEndpointsTest {

    private static final String ADMIN = "admin@hrva.cc";
    private static final String USER = "user@hrva.cc";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    private final AtomicReference<Pageable> received = new AtomicReference<>();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private QrCodeService qrCodeService;

    @MockitoBean
    private LinkPreviewService linkPreviewService;

    @MockitoBean
    private ClickRequestResolver clickRequestResolver;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private EmailLogService emailLogService;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @MockitoBean
    private ReviewQueueService reviewQueueService;

    @MockitoBean
    private ModerationService moderationService;

    @MockitoBean
    private AdminBioPageService adminBioPageService;

    @MockitoBean
    private BioPageReviewService bioPageReviewService;

    @MockitoBean
    private CustomDomainAdminService customDomainAdminService;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    @MockitoBean
    private AdminService adminService;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    @MockitoBean
    private UrlRepository urlRepository;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    static Stream<String> pagedEndpoints() {
        return Stream.of(
                "/api/v1/urls",
                "/api/v1/urls/all",
                "/api/v1/users",
                "/api/v1/api-keys/all",
                "/api/v1/admin/audit-log",
                "/api/v1/admin/email-log",
                "/api/v1/admin/review-queue",
                "/api/v1/admin/bio-pages",
                "/api/v1/admin/bio-page-reviews",
                "/api/v1/admin/domain-rules",
                "/api/v1/admin/domains");
    }

    @BeforeEach
    void setUp() {
        signedIn("admin-token", ADMIN, "ROLE_ADMIN");
        signedIn("user-token", USER, "ROLE_USER");
        lenient().when(urlService.getAllMyUrls(any(), any(Pageable.class), any())).thenAnswer(this::emptyPage);
        lenient().when(urlService.getAllUrls(any(Pageable.class), any())).thenAnswer(this::emptyPage);
        lenient().when(userService.fetchAllUsers(any(Pageable.class), any())).thenAnswer(this::emptyPage);
        lenient().when(apiKeyService.fetchAllApiKeys(any(Pageable.class))).thenAnswer(this::emptyPage);
        lenient().when(auditLogService.getAuditLogs(any(Pageable.class), any())).thenAnswer(this::emptyPage);
        lenient().when(emailLogService.emails(any(), any(Pageable.class))).thenAnswer(this::emptyPage);
        lenient().when(reviewQueueService.openEntries(any(Pageable.class), any(), any())).thenAnswer(this::emptyPage);
        lenient().when(adminBioPageService.search(any(), anyBoolean(), any(Pageable.class))).thenAnswer(this::emptyPage);
        lenient().when(bioPageReviewService.openEntries(any(Pageable.class))).thenAnswer(this::emptyPage);
        lenient().when(moderationService.domainRules(any(), any(), any(Pageable.class))).thenAnswer(this::emptyPage);
        lenient().when(customDomainAdminService.list(any(), any(), any(Pageable.class))).thenAnswer(this::emptyPage);
    }

    @ParameterizedTest
    @MethodSource("pagedEndpoints")
    void aPageLargerThanAHundredRowsIsCutToAHundred(final String path) throws Exception {
        mockMvc.perform(get(path).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + tokenFor(path)).param("page", "2").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100))
                .andExpect(jsonPath("$.page.number").value(2));

        assertThat(received.get().getPageSize()).isEqualTo(100);
        assertThat(received.get().getPageNumber()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("pagedEndpoints")
    void aPageKeepsTheContentAndPageShape(final String path) throws Exception {
        mockMvc.perform(get(path).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + tokenFor(path)).param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0))
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist());
    }

    private Object emptyPage(final InvocationOnMock invocation) {
        final var pageable = Stream.of(invocation.getArguments())
                .filter(Pageable.class::isInstance)
                .map(Pageable.class::cast)
                .findFirst()
                .orElseThrow();
        received.set(pageable);
        return new PageImpl<>(List.of(), pageable, 0);
    }

    private static String tokenFor(final String path) {
        return "/api/v1/urls".equals(path) ? "user-token" : "admin-token";
    }

    private void signedIn(final String token, final String email, final String role) {
        lenient().when(tokenProvider.validateToken(token)).thenReturn(true);
        lenient().when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(User.builder().email(email).active(true).build()));
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
            UrlController.class,
            UserController.class,
            ApiKeyController.class,
            AdminAuditLogController.class,
            AdminEmailLogController.class,
            AdminReviewQueueController.class,
            AdminBioPageController.class,
            AdminBioPageReviewController.class,
            AdminDomainRuleController.class,
            AdminCustomDomainController.class,
            AdminReservedAliasController.class,
            AdminController.class
    })
    static class TestApplication {
    }

}
