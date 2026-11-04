package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = SitePagesEndpointSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=https://hrva.cc"
        })
@AutoConfigureMockMvc
class SitePagesEndpointSecurityTest {

    private static final String FAVICON = "/favicon.ico";
    private static final String FAVICON_SHA1 = "77a979999941d6c451ac6283fff230132b9cedd5";
    private static final String HTML = "text/html;charset=UTF-8";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlService urlService;

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
    void theFaviconIsTheFrontendsIconCachedForThirtyDays() throws Exception {
        final var body = mockMvc.perform(get(FAVICON))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/x-icon"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=2592000, public"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).hasSize(1559);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(body))).isEqualTo(FAVICON_SHA1);
        assertThat(body).isEqualTo(new ClassPathResource("favicon.ico").getContentAsByteArray());
        verifyNoInteractions(urlService);
    }

    @Test
    void aHeadForTheFaviconHasItsHeaders() throws Exception {
        mockMvc.perform(head(FAVICON))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/x-icon"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=2592000, public"));
        verifyNoInteractions(urlService);
    }

    @Test
    void anUnknownPageIsTheSmallNotFoundPageWhateverTheClientAccepts() throws Exception {
        for (final var accept : new String[] {MediaType.TEXT_HTML_VALUE, MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE}) {
            mockMvc.perform(get("/wp-admin/setup-config.php").accept(accept))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(HTML))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, LinkPageHeaders.CACHE_CONTROL))
                    .andExpect(header().string("Content-Security-Policy",
                            "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"))
                    .andExpect(header().string("X-Robots-Tag", LinkPageHeaders.ROBOTS))
                    .andExpect(content().string(containsString("<h1>This link doesn't exist</h1>")))
                    .andExpect(content().string(containsString("<p>hrva.cc has no link with this name.</p>")))
                    .andExpect(content().string(containsString("<a href=\"http://localhost:3000/\">Go to hrva.cc</a>")));
        }
        verifyNoInteractions(urlService, tokenProvider);
    }

    @Test
    void anUnknownPageIsTheSameForASignedInCaller() throws Exception {
        mockMvc.perform(get("/a/b").header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(HTML));
        verifyNoInteractions(urlService, tokenProvider);
    }

    @Test
    void aHeadForAnUnknownPageHasTheStatusAndNoBody() throws Exception {
        mockMvc.perform(head("/a/b/c"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(HTML))
                .andExpect(content().string(""));
    }

    @Test
    void otherMethodsOnAnUnknownPageKeepTheirAnswer() throws Exception {
        mockMvc.perform(post("/a/b"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void theApiAndTheServicePathsKeepTheirAnswers() throws Exception {
        for (final var path : new String[] {"/api/v1/nothing-here", "/api/nothing/here", "/actuator/env", "/.well-known/change-password",
                "/oauth2/nothing/here", "/login/nothing/here", "/error/x"}) {
            mockMvc.perform(get(path).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Test
    void oneSegmentPathsStillReachTheShortLinkRedirect() throws Exception {
        when(urlService.redirectResultUrl(eq("abc"), any())).thenReturn(new RedirectView("https://example.com/"));

        mockMvc.perform(get("/abc/")).andExpect(status().isFound());
        mockMvc.perform(get("/abc")).andExpect(status().isFound());
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
            ClickRequestResolver.class,
            UrlRedirectController.class,
            FaviconController.class
    })
    static class TestApplication {
    }

}
