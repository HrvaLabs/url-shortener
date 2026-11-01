package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.NotSignedInException;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = SignInAnswersSecurityTest.TestApplication.class,
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
class SignInAnswersSecurityTest {

    private static final String USER = "user@hrva.cc";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

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

    @BeforeEach
    void setUp() {
        signedIn("user-token", USER, "ROLE_USER");
    }

    @Test
    void nobodySignedInOnAProtectedRouteIs401AsAProblem() throws Exception {
        mockMvc.perform(get("/api/v1/users/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Sign in to continue."))
                .andExpect(jsonPath("$.instance").value("/api/v1/users/probe"));
    }

    @Test
    void nobodySignedInOnAMethodGuardedPublicRouteIs401NotForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/urls/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Sign in to continue."));
    }

    @Test
    void aSignedInUserWithoutTheRoleOnAMethodGuardedRouteIs403() throws Exception {
        mockMvc.perform(get("/api/v1/urls/probe-admin").header(JwtFilter.AUTHORIZATION_HEADER, "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Access denied"));
    }

    @Test
    void aSignedInUserWithoutTheRoleOnAnAdminRouteIs403AsAProblem() throws Exception {
        mockMvc.perform(get("/api/v1/admin/probe").header(JwtFilter.AUTHORIZATION_HEADER, "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Access denied"))
                .andExpect(jsonPath("$.instance").value("/api/v1/admin/probe"));
    }

    @Test
    void nobodySignedInOnAnAdminRouteIs401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(PROBLEM_JSON));
    }

    @Test
    void aServiceThatFindsNobodySignedInAnswers401() throws Exception {
        mockMvc.perform(get("/api/v1/urls/probe-service"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Not authenticated"));
    }

    @Test
    void aWrongPasswordStaysForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/urls/probe-password"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Incorrect password"));
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

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/users/probe")
        String users() {
            return "ok";
        }

        @GetMapping("/api/v1/admin/probe")
        String admin() {
            return "ok";
        }

        @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
        @GetMapping("/api/v1/urls/probe")
        String mine() {
            return "ok";
        }

        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/api/v1/urls/probe-admin")
        String adminOnly() {
            return "ok";
        }

        @GetMapping("/api/v1/urls/probe-service")
        String service() {
            throw new NotSignedInException("Not authenticated");
        }

        @GetMapping("/api/v1/urls/probe-password")
        String password() {
            throw new NoAuthorizationException("Incorrect password");
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
            ProbeController.class
    })
    static class TestApplication {
    }

}
