package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.SessionToken;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.inspection.InspectionGauges;
import cc.hrva.urlshortener.service.inspection.InspectionUsage;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.stats.ServicePings;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = MetricsEndpointSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "management.endpoints.web.exposure.include=health,prometheus",
                "management.health.redis.enabled=false",
                "management.health.mail.enabled=false",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MetricsEndpointSecurityTest {

    private static final String PROMETHEUS = "/actuator/prometheus";
    private static final String TOKEN = "metrics-S3CRET-token-5b1e";
    private static final String ADMIN_TOKEN = "admin-session";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppProperties appProperties;

    @MockitoBean
    private InspectionUsage inspectionUsage;

    @MockitoBean
    private ServicePings servicePings;

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

    @AfterEach
    void tearDown() {
        appProperties.getMetrics().setToken(null);
    }

    @Test
    void withoutATokenSetTheEndpointIsNotFoundEvenForAnAdmin() throws Exception {
        signedInAsAdmin();

        mockMvc.perform(get(PROMETHEUS))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        mockMvc.perform(get(PROMETHEUS).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        verify(tokenProvider, never()).read(anyString());
    }

    @Test
    void aTrailingSlashGoesThroughTheTokenCheckToo() throws Exception {
        signedInAsAdmin();

        mockMvc.perform(get(PROMETHEUS + "/").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        appProperties.getMetrics().setToken(TOKEN);
        mockMvc.perform(get(PROMETHEUS + "/").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        verify(tokenProvider, never()).read(anyString());
    }

    @Test
    void noTokenOrAWrongOneIs401WithoutADetail() throws Exception {
        appProperties.getMetrics().setToken(TOKEN);
        signedInAsAdmin();

        mockMvc.perform(get(PROMETHEUS))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().string(""));
        mockMvc.perform(get(PROMETHEUS).header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get(PROMETHEUS).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        verify(tokenProvider, never()).read(anyString());
    }

    @Test
    void theRightTokenGetsTheMetrics() throws Exception {
        appProperties.getMetrics().setToken(TOKEN);

        mockMvc.perform(get(PROMETHEUS).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/plain")))
                .andExpect(content().string(containsString("hrva_inspection_queue{queue=\"screenshots\"}")))
                .andExpect(content().string(containsString("hrva_inspection_queue{queue=\"virustotal\"}")))
                .andExpect(content().string(containsString("hrva_browser_used_today_seconds ")))
                .andExpect(content().string(containsString("hrva_virustotal_lookups_today ")))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

    @Test
    void theHealthEndpointStaysPublic() throws Exception {
        appProperties.getMetrics().setToken(TOKEN);

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void theHealthEndpointAnswers406ToAClientThatOnlyAcceptsHtml(final CapturedOutput output) throws Exception {
        mockMvc.perform(get("/actuator/health").header(HttpHeaders.ACCEPT, "text/html"))
                .andExpect(status().isNotAcceptable());

        assertThat(output).doesNotContain("Unhandled").doesNotContain("\tat ");
    }

    @Test
    void otherActuatorEndpointsStayAdminOnly() throws Exception {
        appProperties.getMetrics().setToken(TOKEN);

        mockMvc.perform(get("/actuator").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Sign in to continue."));
    }

    private void signedInAsAdmin() {
        when(tokenProvider.read(ADMIN_TOKEN)).thenReturn(Optional.of(new SessionToken(
                UsernamePasswordAuthenticationToken.authenticated("admin@hrva.cc", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))), null, null, null)));
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
            InspectionGauges.class
    })
    static class TestApplication {
    }

}
