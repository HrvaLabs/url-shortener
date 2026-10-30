package cc.hrva.urlshortener.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.domain.CustomDomainHostnames;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.domain.DomainRoute;
import cc.hrva.urlshortener.service.domain.HostRouting;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = CustomHostEndpointSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=https://app.hrva.cc",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=https://hrva.cc",
                "app.domains.primary-hosts=localhost,127.0.0.1,domains.hrva.cc"
        })
@AutoConfigureMockMvc
class CustomHostEndpointSecurityTest {

    private static final String CUSTOM_HOST = "go.example.com";
    private static final String NO_STORE = "no-cache, no-store, max-age=0, must-revalidate";
    private static final String NOT_FOUND = "Not found";
    private static final DomainRoute GO = new DomainRoute(7L, CUSTOM_HOST, CustomDomainStatus.ACTIVE, true);
    private static final DomainRoute OFF = new DomainRoute(9L, "off.example.com", CustomDomainStatus.DISABLED, true);
    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-29T09:00:00Z"));
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomDomainRegistry customDomainRegistry;

    @MockitoSpyBean
    private FilterChainProxy springSecurityFilterChain;

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

    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(CustomHostFilter.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        when(customDomainRepository.findRoutes()).thenReturn(List.of(
                new DomainRouteRow(7L, CUSTOM_HOST, CUSTOM_HOST, CustomDomainStatus.ACTIVE, 5L, true),
                new DomainRouteRow(9L, "off.example.com", "off.example.com", CustomDomainStatus.DISABLED, 5L, true)));
        customDomainRegistry.reload();
        clearInvocations(springSecurityFilterChain);
        logEvents.start();
        filterLogger.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        filterLogger.detachAppender(logEvents);
    }

    @Test
    void onlyRedirectsAreServedOnACustomHost() throws Exception {
        for (final var request : List.of(
                get("/login"),
                get("/LOGIN"),
                get("/logout"),
                get("/error"),
                get("/api"),
                get("/actuator"),
                get("/oauth2"),
                get("/oauth2/authorization/google"),
                get("/login/oauth2/code/google"),
                get("/oauth2/authorization/github"),
                get("/login/oauth2/code/github"),
                get("/mcp"),
                head("/mcp"),
                post("/mcp"),
                post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.c\",\"password\":\"x\"}"),
                get("/api/v1/users/me"),
                get("/api/v1/urls/menu"),
                get("/actuator/health"),
                get("/@tom"),
                get(URI.create("/%40tom")),
                get("/.well-known/security.txt"),
                post("/menu"),
                put("/menu"),
                patch("/menu"),
                delete("/menu"),
                options("/menu"),
                post("/"),
                get("/a/b"),
                get("/menu/"),
                get("/menu;jsessionid=x"),
                get(URI.create("/menu%3Bjsessionid=x")),
                rawPath("//menu"),
                rawPath("/./menu"))) {
            final var result = mockMvc.perform(on(CUSTOM_HOST, request)).andReturn();
            final var description = result.getRequest().getMethod() + " " + result.getRequest().getRequestURI();
            assertThat(result.getResponse().getStatus()).as(description).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).as(description).isEqualTo(NOT_FOUND);
            assertThat(result.getResponse().getContentType()).as(description).startsWith(MediaType.TEXT_PLAIN_VALUE);
            neverCached().match(result);
        }

        verify(springSecurityFilterChain, never()).doFilter(any(), any(), any());
        verifyNoInteractions(urlService);
    }

    @Test
    void getAndHeadOfAnAliasRedirect() throws Exception {
        when(urlService.redirectResultUrl(eq(GO), eq("menu"), any())).thenReturn(new RedirectView("https://example.com/b"));

        for (final var request : List.of(get("/menu"), head("/menu"), get("/menu?qr"))) {
            mockMvc.perform(on(CUSTOM_HOST, request))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("https://example.com/b"))
                    .andExpect(neverCached());
        }

        verify(springSecurityFilterChain, never()).doFilter(any(), any(), any());
    }

    @Test
    void theAliasIsDecodedOnce() throws Exception {
        when(urlService.redirectResultUrl(eq(GO), any(), any())).thenReturn(new RedirectView("https://example.com/b"));

        mockMvc.perform(on(CUSTOM_HOST, get(URI.create("/m%65nu")))).andExpect(status().isFound());
        mockMvc.perform(on(CUSTOM_HOST, get(URI.create("/a%2540b")))).andExpect(status().isFound());

        verify(urlService).redirectResultUrl(eq(GO), eq("menu"), any());
        verify(urlService).redirectResultUrl(eq(GO), eq("a%40b"), any());
    }

    @Test
    void aTurnedOffDomainIsHandedToTheRedirectSoItsVisitorsAreToldWhy() throws Exception {
        when(urlService.redirectResultUrl(eq(OFF), eq("menu"), any()))
                .thenReturn(new RedirectView("https://app.hrva.cc/unavailable?s=menu&reason=domain&d=off.example.com"));

        mockMvc.perform(on("off.example.com", get("/menu")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://app.hrva.cc/unavailable?s=menu&reason=domain&d=off.example.com"))
                .andExpect(neverCached());
    }

    @Test
    void aProbedPathTheRedirectRefusesIsAPlain404() throws Exception {
        when(urlService.redirectResultUrl(eq(GO), eq("favicon.ico"), any())).thenThrow(new UrlNotFoundException("URL doesn't exist"));

        mockMvc.perform(on(CUSTOM_HOST, get("/favicon.ico")))
                .andExpect(status().isNotFound())
                .andExpect(content().string(NOT_FOUND))
                .andExpect(neverCached());
    }

    @Test
    void theRootGoesToTheFrontend() throws Exception {
        for (final var request : List.of(get("/"), head("/"))) {
            mockMvc.perform(on(CUSTOM_HOST, request))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("https://app.hrva.cc/"))
                    .andExpect(neverCached());
        }

        verifyNoInteractions(urlService);
        verify(springSecurityFilterChain, never()).doFilter(any(), any(), any());
    }

    @Test
    void anUnknownHostIs404AndLoggedOnceAnHour() throws Exception {
        for (final var request : List.of(get("/menu"), get("/login"), get("/"), get("/api/v1/urls/menu"))) {
            mockMvc.perform(on("unknown.example.org", request))
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(NOT_FOUND))
                    .andExpect(neverCached());
        }
        CLOCK.advance(Duration.ofMinutes(59));
        mockMvc.perform(on("unknown.example.org", get("/menu"))).andExpect(status().isNotFound());
        mockMvc.perform(on("other.example.org", get("/menu"))).andExpect(status().isNotFound());
        CLOCK.advance(Duration.ofMinutes(2));
        mockMvc.perform(on("unknown.example.org", get("/menu"))).andExpect(status().isNotFound());

        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Unknown host refused host=unknown.example.org",
                "Unknown host refused host=other.example.org",
                "Unknown host refused host=unknown.example.org");
        verifyNoInteractions(urlService);
        verify(springSecurityFilterChain, never()).doFilter(any(), any(), any());
    }

    @Test
    void aPendingDomainIs404() throws Exception {
        mockMvc.perform(on("pending.example.com", get("/menu")))
                .andExpect(status().isNotFound())
                .andExpect(content().string(NOT_FOUND));

        verifyNoInteractions(urlService);
    }

    @Test
    void forwardedHostIsIgnored() throws Exception {
        when(urlService.redirectResultUrl(eq("menu"), any())).thenReturn(new RedirectView("https://example.com/a"));

        mockMvc.perform(on("hrva.cc", get("/menu")).header("X-Forwarded-Host", CUSTOM_HOST).header("Forwarded", "host=" + CUSTOM_HOST))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/a"))
                .andExpect(neverCached());

        verify(urlService).redirectResultUrl(eq("menu"), any());
        verify(urlService, never()).redirectResultUrl(any(DomainRoute.class), any(), any());
    }

    @Test
    void aForwardedPrimaryHostDoesNotTurnACustomHostIntoHrva() throws Exception {
        when(urlService.redirectResultUrl(eq(GO), eq("menu"), any())).thenReturn(new RedirectView("https://example.com/b"));

        mockMvc.perform(on(CUSTOM_HOST, get("/menu")).header("X-Forwarded-Host", "hrva.cc").header("Forwarded", "host=hrva.cc"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/b"))
                .andExpect(neverCached());
        mockMvc.perform(on(CUSTOM_HOST, get("/login")).header("X-Forwarded-Host", "hrva.cc"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(NOT_FOUND));

        verify(urlService, never()).redirectResultUrl(eq("menu"), any());
        verify(springSecurityFilterChain, never()).doFilter(any(), any(), any());
    }

    @Test
    void anErrorDispatchOnACustomOrUnknownHostIsAPlain404AndNeverTheErrorPage() throws Exception {
        for (final var host : List.of(CUSTOM_HOST, "stray.example.org")) {
            mockMvc.perform(on(host, errorDispatchFor("/menu")))
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(NOT_FOUND))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                    .andExpect(neverCached());
        }

        verifyNoInteractions(urlService);
        verify(springSecurityFilterChain, never()).doFilter(any(), any(), any());
    }

    @Test
    void anErrorDispatchOnAPrimaryHostStillReachesTheApp() throws Exception {
        mockMvc.perform(on("hrva.cc", errorDispatchFor("/menu")));

        verify(springSecurityFilterChain, atLeastOnce()).doFilter(any(), any(), any());
    }

    @Test
    void primaryHostsWorkAsBefore() throws Exception {
        final var link = UrlResponse.from(Url.builder().id(1L).shortUrl("menu").longUrl("https://example.com/a").active(true).build());
        when(urlService.revealUrlAndRecordClick(isNull(), eq("menu"), any())).thenReturn(link);
        when(urlService.redirectResultUrl(eq("login"), any())).thenReturn(new RedirectView("https://app.hrva.cc/unavailable?s=login&reason=missing"));

        for (final var host : List.of("localhost", "127.0.0.1", "domains.hrva.cc", "hrva.cc", "HRVA.cc.")) {
            mockMvc.perform(on(host, get("/api/v1/urls/menu")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
            mockMvc.perform(on(host, get("/login")))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("https://app.hrva.cc/unavailable?s=login&reason=missing"))
                    .andExpect(neverCached());
            mockMvc.perform(on(host, get("/api/v1/users/me")))
                    .andExpect(status().isUnauthorized());
        }

        verify(springSecurityFilterChain, atLeastOnce()).doFilter(any(), any(), any());
    }

    private static MockHttpServletRequestBuilder rawPath(final String path) {
        return get("/").with(request -> {
            request.setRequestURI(path);
            return request;
        });
    }

    private static MockHttpServletRequestBuilder errorDispatchFor(final String failedPath) {
        return get("/error").with(request -> {
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, failedPath);
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
            request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Bad Request");
            return request;
        });
    }

    private static MockHttpServletRequestBuilder on(final String host, final MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.HOST, host);
    }

    private static ResultMatcher neverCached() {
        return result -> {
            header().string(HttpHeaders.CACHE_CONTROL, NO_STORE).match(result);
            header().string(HttpHeaders.PRAGMA, "no-cache").match(result);
            header().string(HttpHeaders.EXPIRES, "0").match(result);
            header().string("X-Content-Type-Options", "nosniff").match(result);
        };
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
            UrlRedirectController.class,
            TestApplication.FixedClock.class
    })
    static class TestApplication {

        @TestConfiguration
        static class FixedClock {

            @Bean
            MutableClock clock() {
                return CLOCK;
            }

        }

    }

}
