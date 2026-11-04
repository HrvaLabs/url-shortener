package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.service.stats.RecentRequests;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.DomainRouteRow;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.CustomHostFilter;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.ServerTimingFilter;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.domain.CustomDomainHostnames;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.domain.DomainRoute;
import cc.hrva.urlshortener.service.domain.HostRouting;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = UnknownPageEndpointTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=https://app.hrva.cc",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=https://hrva.cc",
                "app.domains.primary-hosts=localhost,127.0.0.1"
        })
@AutoConfigureMockMvc
class UnknownPageEndpointTest {

    private static final String CUSTOM_HOST = "go.example.com";
    private static final DomainRoute GO = new DomainRoute(7L, CUSTOM_HOST, CustomDomainStatus.ACTIVE, true);
    private static final RSAKey SIGNING_KEY = generateSigningKey();
    private static final String EXPECTED_PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="light dark">
            <meta name="robots" content="noindex, nofollow">
            <title>Link not found &middot; hrva.cc</title>
            </head>
            <body>
            <h1>This link doesn't exist</h1>
            <p>hrva.cc has no link with this name.</p>
            <p><a href="https://app.hrva.cc/">Go to hrva.cc</a></p>
            </body>
            </html>
            """;
    private static final List<String> PATHS_THAT_CANNOT_BE_LINKS = List.of(
            "/.env", "/config.php.bak", "/config.php.bak/", "/wp/v2/posts/1", "/ssl/server.key", "/.git", "/sitemap.xml", "/favicon.png", "/wp-login.php",
            "/.env?x=1", "/a%20b", "/my.link");

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomDomainRegistry customDomainRegistry;

    @MockitoSpyBean
    private DataSource dataSource;

    @MockitoSpyBean
    private RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    private CustomDomainRepository customDomainRepository;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private QrCodeService qrCodeService;

    @MockitoBean
    private LinkPreviewService linkPreviewService;

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

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        when(customDomainRepository.findRoutes()).thenReturn(List.of(
                new DomainRouteRow(7L, CUSTOM_HOST, CUSTOM_HOST, CustomDomainStatus.ACTIVE, 5L, true)));
        customDomainRegistry.reload();
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
        clearInvocations(dataSource, redisConnectionFactory, customDomainRepository, urlService, userRepository, tokenProvider);
    }

    @Test
    void aPathThatCannotBeALinkAnswersTheSmallPageWithoutAnyLookup() throws Exception {
        for (final var path : PATHS_THAT_CANNOT_BE_LINKS) {
            final var answer = send("GET", path, "python-requests/2.32.3");

            assertThat(answer.statusCode()).as(path).isEqualTo(404);
            assertThat(answer.body()).as(path).isEqualTo(EXPECTED_PAGE);
            assertThat(answer.headers().allValues(HttpHeaders.CONTENT_TYPE)).as(path).containsExactly("text/html;charset=UTF-8");
            assertThat(answer.headers().allValues(HttpHeaders.CACHE_CONTROL)).as(path).containsExactly(LinkPageHeaders.CACHE_CONTROL);
            assertThat(answer.headers().allValues("Content-Security-Policy")).as(path).containsExactly(LinkPageHeaders.CONTENT_SECURITY_POLICY);
            assertThat(answer.headers().allValues("X-Robots-Tag")).as(path).containsExactly("noindex, nofollow");
            assertThat(answer.headers().allValues("X-Content-Type-Options")).as(path).containsExactly("nosniff");
            assertThat(answer.headers().allValues("Server-Timing")).as(path).singleElement().asString().matches("app;dur=\\d+\\.\\d");
            assertThat(answer.headers().firstValue(HttpHeaders.CONTENT_LENGTH)).as(path)
                    .hasValue(String.valueOf(EXPECTED_PAGE.getBytes(StandardCharsets.UTF_8).length));
        }

        verifyNoInteractions(urlService, dataSource, redisConnectionFactory, customDomainRepository, userRepository, tokenProvider);
    }

    @Test
    void aHeadRequestGetsTheHeadersAndNoBody() throws Exception {
        final var answer = send("HEAD", "/config.php.bak", "python-requests/2.32.3");

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.body()).isEmpty();
        assertThat(answer.headers().firstValue(HttpHeaders.CONTENT_LENGTH)).hasValue(String.valueOf(EXPECTED_PAGE.getBytes(StandardCharsets.UTF_8).length));
        assertThat(answer.headers().firstValue(HttpHeaders.CONTENT_TYPE)).hasValue("text/html;charset=UTF-8");

        verifyNoInteractions(urlService, dataSource, redisConnectionFactory, customDomainRepository);
    }

    @Test
    void aJsonClientGetsThePageToo() throws Exception {
        final var answer = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/config.php.bak"))
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.body()).isEqualTo(EXPECTED_PAGE);
    }

    @Test
    void aWriteToAPathThatCannotBeALinkIsStillRefusedBySecurity() throws Exception {
        assertThat(send("POST", "/.env", "scanner").statusCode()).isEqualTo(401);
        assertThat(send("POST", "/ssl/server.key", "scanner").statusCode()).isEqualTo(401);
    }

    @Test
    void anAliasStillGoesToTheRedirect() throws Exception {
        when(urlService.redirectResultUrl(eq("abc12"), any())).thenReturn(new RedirectView("https://example.com/a"));
        when(urlService.redirectResultUrl(eq("A_b-9"), any())).thenReturn(new RedirectView("https://example.com/b"));

        mockMvc.perform(get("/abc12")).andExpect(status().isFound()).andExpect(redirectedUrl("https://example.com/a"));
        mockMvc.perform(get("/abc12/")).andExpect(status().isFound()).andExpect(redirectedUrl("https://example.com/a"));
        mockMvc.perform(get(URI.create("/%61bc12"))).andExpect(status().isFound()).andExpect(redirectedUrl("https://example.com/a"));
        mockMvc.perform(get("/A_b-9")).andExpect(status().isFound()).andExpect(redirectedUrl("https://example.com/b"));

        verify(urlService, times(3)).redirectResultUrl(eq("abc12"), any());
        verify(urlService).redirectResultUrl(eq("A_b-9"), any());
    }

    @Test
    void aLinkSavedBeforeTheAliasRuleStillGoesToTheRedirect() throws Exception {
        when(urlService.redirectResultUrl(eq("\u010duj mali"), any())).thenReturn(new RedirectView("https://example.com/c"));

        mockMvc.perform(get(URI.create("/%C4%8Duj%20mali"))).andExpect(status().isFound()).andExpect(redirectedUrl("https://example.com/c"));

        verify(urlService).redirectResultUrl(eq("\u010duj mali"), any());
    }

    @Test
    void theNamesTheBotProbeHandlingCoversStillReachTheRedirect() throws Exception {
        when(urlService.redirectResultUrl(any(String.class), any()))
                .thenReturn(LinkUnavailableView.of(LinkWarningTemplates.engine(), "https://app.hrva.cc", "https://hrva.cc", "x", null, "missing").asPageOnly());

        for (final var alias : List.of("robots.txt", ".well-known", "wp-admin")) {
            mockMvc.perform(get("/" + alias)).andExpect(status().isNotFound()).andExpect(content().string(containsString("This link isn&#39;t available")));
            verify(urlService).redirectResultUrl(eq(alias), any());
        }
    }

    @Test
    void theRealRoutesBehaveAsBefore() throws Exception {
        final var link = UrlResponse.from(Url.builder().id(1L).shortUrl("menu").longUrl("https://example.com/a").active(true).build());
        when(urlService.revealUrlAndRecordClick(any(), eq("menu"), any())).thenReturn(link);

        mockMvc.perform(get("/favicon.ico")).andExpect(status().isOk()).andExpect(content().contentType("image/x-icon"));
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isFound()).andExpect(header().string(HttpHeaders.LOCATION, "/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/.well-known/security.txt")).andExpect(status().isOk());
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp")).andExpect(status().isOk());
        mockMvc.perform(get("/mcp")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/urls/menu")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/oauth2/jwks")).andExpect(status().isOk());
        mockMvc.perform(get("/oauth2/authorization/google")).andExpect(status().isFound());
        mockMvc.perform(get("/login/oauth2/code/google")).andExpect(status().isFound());
        mockMvc.perform(get("/@Ana")).andExpect(status().isFound()).andExpect(redirectedUrl("https://app.hrva.cc/bio/ana"));
        mockMvc.perform(get(URI.create("/%40ana"))).andExpect(status().isFound()).andExpect(redirectedUrl("https://app.hrva.cc/bio/ana"));
        mockMvc.perform(get("/")).andExpect(status().isMovedPermanently()).andExpect(redirectedUrl("https://app.hrva.cc/"));
    }

    @Test
    void aCustomHostKeepsItsOwnHandling() throws Exception {
        when(urlService.redirectResultUrl(eq(GO), eq("config.php.bak"), any())).thenThrow(new UrlNotFoundException("URL doesn't exist"));
        when(urlService.redirectResultUrl(eq(GO), eq(".env"), any())).thenThrow(new UrlNotFoundException("URL doesn't exist"));
        when(urlService.redirectResultUrl(eq(GO), eq("menu"), any())).thenReturn(new RedirectView("https://example.com/b"));

        mockMvc.perform(get("/config.php.bak").header(HttpHeaders.HOST, CUSTOM_HOST))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Not found"));
        mockMvc.perform(get("/.env").header(HttpHeaders.HOST, CUSTOM_HOST))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/menu").header(HttpHeaders.HOST, CUSTOM_HOST))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/b"));

        verify(urlService).redirectResultUrl(eq(GO), eq("config.php.bak"), any());
        verify(urlService).redirectResultUrl(eq(GO), eq(".env"), any());
        verify(urlService, never()).redirectResultUrl(eq(".env"), any());
        verify(urlService, never()).redirectResultUrl(eq("config.php.bak"), any());
    }

    private HttpResponse<String> send(final String method, final String path, final String userAgent) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.USER_AGENT, userAgent)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
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
            ClickRequestResolver.class,
            CustomDomainHostnames.class,
            CustomDomainRegistry.class,
            HostRouting.class,
            CustomHostFilter.class,
            UrlController.class,
            ShortLinks.class,
            UrlRedirectController.class,
            FaviconController.class,
            BioRedirectController.class,
            SecurityTxtController.class,
            ServerTimingFilter.class,
            RecentRequests.class,
            TestApplication.FixedClock.class
    })
    static class TestApplication {

        @TestConfiguration
        static class FixedClock {

            @Bean
            MutableClock clock() {
                return new MutableClock(Instant.parse("2026-10-29T09:00:00Z"));
            }

        }

    }

}
