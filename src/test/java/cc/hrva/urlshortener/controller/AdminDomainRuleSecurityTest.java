package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.DomainBlockPreviewResponse;
import cc.hrva.urlshortener.dto.DomainBulkBlockResponse;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
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
        classes = AdminDomainRuleSecurityTest.TestApplication.class,
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
class AdminDomainRuleSecurityTest {

    private static final String BULK_PREVIEW = "/api/v1/admin/domain-rules/bulk-preview";
    private static final String BULK = "/api/v1/admin/domain-rules/bulk";
    private static final String PREVIEW_BODY = "{\"urlIds\":[287,288]}";
    private static final String BULK_BODY = "{\"domains\":[\"omaehhm.com\",\"fvvxctw.com\"],\"reason\":\"Phishing\"}";
    private static final String ADMIN = "admin@hrva.cc";
    private static final String USER = "user@hrva.cc";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModerationService moderationService;

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
    void anAdminCanPreviewTheDomainsOfSeveralLinks() throws Exception {
        when(moderationService.previewDomainBlocks(List.of(287L, 288L)))
                .thenReturn(List.of(new DomainBlockPreviewResponse("omaehhm.com", List.of("ll.omaehhm.com"), 2, 3, null)));

        mockMvc.perform(signedInPost(BULK_PREVIEW, "admin-token").content(PREVIEW_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].domain").value("omaehhm.com"))
                .andExpect(jsonPath("$[0].activeLinks").value(3));
    }

    @Test
    void anAdminCanBlockSeveralDomainsAtOnce() throws Exception {
        when(moderationService.blockDomains(List.of("omaehhm.com", "fvvxctw.com"), "Phishing", ADMIN, "admin-bulk"))
                .thenReturn(new DomainBulkBlockResponse(List.of(new DomainBulkBlockResponse.Blocked("omaehhm.com", 2),
                        new DomainBulkBlockResponse.Blocked("fvvxctw.com", 1)), List.of(), 3));

        mockMvc.perform(signedInPost(BULK, "admin-token").content(BULK_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked.length()").value(2))
                .andExpect(jsonPath("$.linksDeactivated").value(3));
    }

    @Test
    void aSignedInUserWhoIsNotAnAdminIsForbidden() throws Exception {
        mockMvc.perform(signedInPost(BULK_PREVIEW, "user-token").content(PREVIEW_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(signedInPost(BULK, "user-token").content(BULK_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(moderationService);
    }

    @Test
    void nobodySignedInIsUnauthorized() throws Exception {
        mockMvc.perform(post(BULK_PREVIEW).contentType(MediaType.APPLICATION_JSON).content(PREVIEW_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BULK).contentType(MediaType.APPLICATION_JSON).content(BULK_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(moderationService);
    }

    @Test
    void moreThanAHundredLinksOrDomainsAreABadRequestThroughTheRealStack() throws Exception {
        final var ids = LongStream.rangeClosed(1, 101).boxed().toList();
        final var domains = IntStream.rangeClosed(1, 101).mapToObj("\"d%d.com\""::formatted).toList();

        mockMvc.perform(signedInPost(BULK_PREVIEW, "admin-token").content("{\"urlIds\":" + ids + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.urlIds").value("Select between 1 and 100 links"));
        mockMvc.perform(signedInPost(BULK, "admin-token").content("{\"domains\":" + domains + ",\"reason\":\"Phishing\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.domains").value("Select between 1 and 100 domains"));

        verifyNoInteractions(moderationService);
    }

    @Test
    void aBulkBlockWithoutAReasonIsABadRequestThroughTheRealStack() throws Exception {
        mockMvc.perform(signedInPost(BULK, "admin-token").content("{\"domains\":[\"omaehhm.com\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("Reason is required"));

        verifyNoInteractions(moderationService);
    }

    private void signedIn(final String token, final String email, final String role) {
        lenient().when(tokenProvider.validateToken(token)).thenReturn(true);
        lenient().when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(User.builder().email(email).active(true).build()));
    }

    private static MockHttpServletRequestBuilder signedInPost(final String path, final String token) {
        return post(path).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
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
            AdminDomainRuleController.class
    })
    static class TestApplication {
    }

}
