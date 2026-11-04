package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.MetricsSecurityConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.SystemStatusResponse;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.SessionToken;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.AdminService;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.SystemStatusService;
import cc.hrva.urlshortener.service.jobs.JobOutcome;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminSystemStatusSecurityTest.TestApplication.class,
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
class AdminSystemStatusSecurityTest {

    private static final String SYSTEM_STATUS = "/api/v1/admin/system-status";
    private static final RSAKey SIGNING_KEY = generateSigningKey();
    private static final SystemStatusResponse STATUS = new SystemStatusResponse(
            new SystemStatusResponse.Summary(false, List.of("Screenshots: today's limit is used up.")),
            new SystemStatusResponse.Traffic(60, 120, 80, 3.0, 40.0, 0),
            new SystemStatusResponse.Database(true, 1.2, 0, 10),
            new SystemStatusResponse.Redis(true, 0.8, null),
            new SystemStatusResponse.Email(LocalDateTime.of(2026, 11, 2, 15, 25), 0),
            new SystemStatusResponse.Screenshots(true, true, 600.5, 600, 0L, LocalDateTime.of(2026, 11, 4, 0, 0)),
            new SystemStatusResponse.VirusTotal(false, true, 0L, 480, 0L, null),
            new SystemStatusResponse.Blocklists(LocalDateTime.of(2026, 11, 3, 21, 0), List.of()),
            new SystemStatusResponse.Server("v2.30", LocalDateTime.of(2026, 11, 3, 21, 28, 6), 3600L, 25.5, 430_000_000L, -1L, null),
            List.of(new SystemStatusResponse.Job("deactivateExpiredUrls", "Turn off expired links", null, null, null, null),
                    new SystemStatusResponse.Job("refreshDueBlocklists", "Refresh due blocklists", LocalDateTime.of(2026, 11, 3, 22, 0),
                            LocalDateTime.of(2026, 11, 3, 22, 0, 4), JobOutcome.FAILED, "java.lang.IllegalStateException: Redis is down")));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemStatusService systemStatusService;

    @MockitoBean
    private AdminService adminService;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    @MockitoBean
    private SettingsService settingsService;

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

    @Test
    void nobodySignedInGets401() throws Exception {
        mockMvc.perform(get(SYSTEM_STATUS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Sign in to continue."));

        verifyNoInteractions(systemStatusService);
    }

    @Test
    void aUserGets403() throws Exception {
        signedIn("user-token", "ana@example.com", true, "ROLE_USER");

        mockMvc.perform(get(SYSTEM_STATUS).header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access denied"));

        verifyNoInteractions(systemStatusService);
    }

    @Test
    void anAdminWithoutTwoStepGets403AndIsToldWhy() throws Exception {
        signedIn("admin-token", "admin@hrva.cc", false, "ROLE_USER", "ROLE_ADMIN");

        mockMvc.perform(get(SYSTEM_STATUS).header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Turn on two-step sign-in to use admin pages."));

        verifyNoInteractions(systemStatusService);
    }

    @Test
    void anAdminWithTwoStepGetsTheStatusWithEveryKeyNullsIncluded() throws Exception {
        signedIn("admin-token", "admin@hrva.cc", true, "ROLE_USER", "ROLE_ADMIN");
        when(systemStatusService.status()).thenReturn(STATUS);

        mockMvc.perform(get(SYSTEM_STATUS).header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$.summary.ok").value(false))
                .andExpect(jsonPath("$.summary.problems[0]").value("Screenshots: today's limit is used up."))
                .andExpect(jsonPath("$.traffic.windowMinutes").value(60))
                .andExpect(jsonPath("$.traffic.requests").value(120))
                .andExpect(jsonPath("$.traffic.redirects").value(80))
                .andExpect(jsonPath("$.traffic.p50Ms").value(3.0))
                .andExpect(jsonPath("$.traffic.p95Ms").value(40.0))
                .andExpect(jsonPath("$.traffic.serverErrors").value(0))
                .andExpect(jsonPath("$.database.up").value(true))
                .andExpect(jsonPath("$.database.latencyMs").value(1.2))
                .andExpect(jsonPath("$.database.poolActive").value(0))
                .andExpect(jsonPath("$.database.poolMax").value(10))
                .andExpect(jsonPath("$.redis.up").value(true))
                .andExpect(jsonPath("$.redis.hitRatio").value(nullValue()))
                .andExpect(jsonPath("$.redis.length()").value(3))
                .andExpect(jsonPath("$.email.lastSentAt").value("2026-11-02T15:25:00Z"))
                .andExpect(jsonPath("$.email.failures24h").value(0))
                .andExpect(jsonPath("$.screenshots.configured").value(true))
                .andExpect(jsonPath("$.screenshots.enabled").value(true))
                .andExpect(jsonPath("$.screenshots.browserSecondsToday").value(600.5))
                .andExpect(jsonPath("$.screenshots.browserSecondsLimit").value(600))
                .andExpect(jsonPath("$.screenshots.queued").value(0))
                .andExpect(jsonPath("$.screenshots.pausedUntil").value("2026-11-04T00:00:00Z"))
                .andExpect(jsonPath("$.virusTotal.configured").value(false))
                .andExpect(jsonPath("$.virusTotal.lookupsToday").value(0))
                .andExpect(jsonPath("$.virusTotal.dailyLimit").value(480))
                .andExpect(jsonPath("$.virusTotal.pausedUntil").value(nullValue()))
                .andExpect(jsonPath("$.virusTotal.length()").value(6))
                .andExpect(jsonPath("$.blocklists.lastRefreshAt").value("2026-11-03T21:00:00Z"))
                .andExpect(jsonPath("$.blocklists.stale").isEmpty())
                .andExpect(jsonPath("$.server.version").value("v2.30"))
                .andExpect(jsonPath("$.server.startedAt").value("2026-11-03T21:28:06Z"))
                .andExpect(jsonPath("$.server.uptimeSeconds").value(3600))
                .andExpect(jsonPath("$.server.startupSeconds").value(25.5))
                .andExpect(jsonPath("$.server.heapUsedBytes").value(430_000_000))
                .andExpect(jsonPath("$.server.heapMaxBytes").value(-1))
                .andExpect(jsonPath("$.server.gcPauseMsTotal").value(nullValue()))
                .andExpect(jsonPath("$.server.length()").value(7))
                .andExpect(jsonPath("$.jobs[0].name").value("deactivateExpiredUrls"))
                .andExpect(jsonPath("$.jobs[0].label").value("Turn off expired links"))
                .andExpect(jsonPath("$.jobs[0].lastStartedAt").value(nullValue()))
                .andExpect(jsonPath("$.jobs[0].lastOutcome").value(nullValue()))
                .andExpect(jsonPath("$.jobs[0].length()").value(6))
                .andExpect(jsonPath("$.jobs[1].lastStartedAt").value("2026-11-03T22:00:00Z"))
                .andExpect(jsonPath("$.jobs[1].lastFinishedAt").value("2026-11-03T22:00:04Z"))
                .andExpect(jsonPath("$.jobs[1].lastOutcome").value("FAILED"))
                .andExpect(jsonPath("$.jobs[1].lastError").value("java.lang.IllegalStateException: Redis is down"));
    }

    private void signedIn(final String token, final String email, final boolean twoStep, final String... roles) {
        when(tokenProvider.read(token)).thenReturn(Optional.of(new SessionToken(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()),
                null, null, null)));
        final var authorities = Arrays.stream(roles).map(role -> Authorities.builder().name(role).active(true).build()).toList();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(User.builder().id(1L).email(email).active(true).twoStepEnabled(twoStep)
                .authorities(authorities).build()));
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
            MetricsSecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            AdminController.class
    })
    static class TestApplication {
    }

}
