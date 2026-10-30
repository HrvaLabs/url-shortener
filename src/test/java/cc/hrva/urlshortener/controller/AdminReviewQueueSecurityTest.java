package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.ReviewBulkResolveResponse;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ReviewAction;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminReviewQueueSecurityTest.TestApplication.class,
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
class AdminReviewQueueSecurityTest {

    private static final String BULK_RESOLVE = "/api/v1/admin/review-queue/resolve";
    private static final String BODY = "{\"urlIds\":[287,288],\"action\":\"BLOCK_LINK\"}";
    private static final String ADMIN = "admin@hrva.cc";
    private static final String USER = "user@hrva.cc";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModerationService moderationService;

    @MockitoBean
    private ReviewQueueService reviewQueueService;

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

    @BeforeEach
    void setUp() {
        signedIn("admin-token", ADMIN, "ROLE_ADMIN");
        signedIn("user-token", USER, "ROLE_USER");
    }

    @Test
    void anAdminCanResolveSeveralEntriesAtOnce() throws Exception {
        when(moderationService.resolveReviews(List.of(287L, 288L), ReviewAction.BLOCK_LINK, ADMIN, "admin"))
                .thenReturn(new ReviewBulkResolveResponse(List.of(287L, 288L), List.of()));

        mockMvc.perform(bulkResolve("admin-token").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved.length()").value(2));
    }

    @Test
    void aSignedInUserWhoIsNotAnAdminIsForbidden() throws Exception {
        mockMvc.perform(bulkResolve("user-token").content(BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(moderationService);
    }

    @Test
    void nobodySignedInIsUnauthorized() throws Exception {
        mockMvc.perform(post(BULK_RESOLVE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(moderationService);
    }

    @Test
    void anUnknownActionIsABadRequestThroughTheRealStack() throws Exception {
        for (final var action : List.of("\"DELETE_EVERYTHING\"", "1")) {
            mockMvc.perform(bulkResolve("admin-token").content("{\"urlIds\":[287],\"action\":" + action + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Action must be OK, TRUST_DOMAIN, BLOCK_LINK, BLOCK_DOMAIN or BLOCK_USER"));
        }

        verifyNoInteractions(moderationService);
    }

    @Test
    void moreThanAHundredEntriesAreABadRequestThroughTheRealStack() throws Exception {
        final var ids = LongStream.rangeClosed(1, 101).boxed().toList();

        mockMvc.perform(bulkResolve("admin-token").content("{\"urlIds\":" + ids + ",\"action\":\"OK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.urlIds").value("Select between 1 and 100 links"));

        verifyNoInteractions(moderationService);
    }

    private void signedIn(final String token, final String email, final String role) {
        lenient().when(tokenProvider.validateToken(token)).thenReturn(true);
        lenient().when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(User.builder().email(email).active(true).build()));
    }

    private static MockHttpServletRequestBuilder bulkResolve(final String token) {
        return post(BULK_RESOLVE).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
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
            AdminReviewQueueController.class
    })
    static class TestApplication {
    }

}
