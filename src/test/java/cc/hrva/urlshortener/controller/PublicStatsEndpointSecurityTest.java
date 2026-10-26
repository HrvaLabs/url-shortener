package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.BlockedLinks;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.DailyClicks;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.PublicStatsService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = PublicStatsEndpointSecurityTest.TestApplication.class,
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
class PublicStatsEndpointSecurityTest {

    private static final String PUBLIC_STATS = "/api/v1/public/stats";
    private static final RSAKey SIGNING_KEY = generateSigningKey();
    private static final LocalDate FROM = LocalDate.of(2026, 9, 26);
    private static final LocalDate TO = LocalDate.of(2026, 10, 25);
    private static final PublicStatsResponse STATS = new PublicStatsResponse(FROM, TO, 700, 40, 1200, List.of(new DailyClicks(FROM, 30)),
            new BlockedLinks(12, 3), 4, 210L, List.of(), LocalDateTime.of(2026, 10, 26, 10, 0));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicStatsService publicStatsService;

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

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), eq(Duration.ofHours(1)))).thenReturn(true);
    }

    @Test
    void anyoneCanReadThePublicNumbers() throws Exception {
        when(publicStatsService.stats()).thenReturn(STATS);

        mockMvc.perform(get(PUBLIC_STATS))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=600, public"))
                .andExpect(jsonPath("$.from").value("2026-09-26"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-26T10:00:00"))
                .andExpect(jsonPath("$.averageReviewMinutes").value(210));
    }

    @Test
    void aStaleTokenDoesNotBreakIt() throws Exception {
        when(publicStatsService.stats()).thenReturn(STATS);
        when(tokenProvider.validateToken("expired.jwt.token")).thenReturn(false);

        mockMvc.perform(get(PUBLIC_STATS).header(HttpHeaders.AUTHORIZATION, "Bearer expired.jwt.token"))
                .andExpect(status().isOk());
    }

    @Test
    void onlyGetIsAnswered() throws Exception {
        mockMvc.perform(post(PUBLIC_STATS))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"));
        mockMvc.perform(delete(PUBLIC_STATS))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(publicStatsService, rateLimiter);
    }

    @Test
    void otherPathsUnderPublicAreNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/public/stats/links"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(publicStatsService);
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
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            PublicStatsController.class
    })
    static class TestApplication {
    }

}
