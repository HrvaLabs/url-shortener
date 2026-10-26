package cc.hrva.urlshortener.mcp;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.controller.UrlRedirectController;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.mcp.tools.LinkTools;
import cc.hrva.urlshortener.mcp.tools.McpUserResolver;
import cc.hrva.urlshortener.mcp.tools.ModerationTools;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.ratelimit.RedisFixedWindowRateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = UnauthenticatedEndpointsTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=apGSsEZu0Olb7Yg+5fX8BSmN8Fk/UTXzdN0wnWq+EHLW2fT4ss1KuzwV2xo3V2uC/3ZUEphbQUbSl0VBs3bX4g==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class UnauthenticatedEndpointsTest {

    private static final String ISSUER = "https://hrva.cc";
    private static final String RESOURCE = ISSUER + "/mcp";
    private static final String REMOVED_ANONYMOUS_MCP = "/mcp/anon";
    private static final String EMAIL = "user@localhost";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String MCP_ACCEPT = "application/json, text/event-stream";
    private static final int BODY_LIMIT = 64 * 1024;
    private static final String REQUEST_TOO_LARGE = "{\"error\":\"request_too_large\"}";
    private static final String REGISTRATION = """
            {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none"}""";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":3,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0.0"}}}""";
    private static final List<HttpMethod> METHODS = List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE);
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenProvider tokenProvider;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AnalyticsService analyticsService;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private ModerationService moderationService;

    @MockitoBean
    private ReviewQueueService reviewQueueService;

    @MockitoBean
    private ScammerSignalService scammerSignalService;

    @MockitoBean
    private LinkCheckService linkCheckService;

    @MockitoBean
    private BlocklistSourceService blocklistSourceService;

    @MockitoBean
    private UrlRepository urlRepository;

    @MockitoBean
    private LinkReviewRepository linkReviewRepository;

    private final InMemoryRedis redis = new InMemoryRedis();
    private McpAccessTokens accessTokens;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        redis.backing(redisTemplate);
        accessTokens = new McpAccessTokens(mockMvc);
        final var user = User.builder().id(7L).email(EMAIL).active(true).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(user);
    }

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
            "GET, NOBODY", "GET, MCP_ACCESS_TOKEN", "GET, APP_JWT",
            "POST, NOBODY", "POST, MCP_ACCESS_TOKEN", "POST, APP_JWT",
            "DELETE, NOBODY", "DELETE, MCP_ACCESS_TOKEN", "DELETE, APP_JWT"
    })
    void theRemovedAnonymousServerIs404ForEveryMethodAndToken(final String method, final Caller caller) throws Exception {
        final var token = tokenOf(caller);

        mockMvc.perform(removedServerRequest(HttpMethod.valueOf(method), token))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(this::hasNoJavaInternals);

        verify(urlService, never()).redirectResultUrl(any(), any());
    }

    @Test
    void pathsBelowTheRemovedServerAre404() throws Exception {
        mockMvc.perform(jsonPost(REMOVED_ANONYMOUS_MCP + "/x", INITIALIZE))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(this::hasNoJavaInternals);
    }

    @Test
    void theRemovedServerLogsNothingAlarming(final CapturedOutput output) throws Exception {
        final var tokens = List.of(tokenOf(Caller.NOBODY), tokenOf(Caller.MCP_ACCESS_TOKEN), tokenOf(Caller.APP_JWT));
        final var outputBeforeRequests = output.length();

        for (final var method : METHODS) {
            for (final var token : tokens) {
                mockMvc.perform(removedServerRequest(method, token))
                        .andExpect(status().isNotFound());
            }
        }
        mockMvc.perform(jsonPost(REMOVED_ANONYMOUS_MCP + "/x", INITIALIZE))
                .andExpect(status().isNotFound());

        assertThat(output.toString().substring(outputBeforeRequests))
                .doesNotContain("ERROR")
                .doesNotContain("\tat ")
                .doesNotContain("Unhandled exception");
    }

    @Test
    void unsupportedMethodsAre405AndUnknownPathsAre404WithoutErrorLogs(final CapturedOutput output) throws Exception {
        final var appJwt = appJwt();
        final var outputBeforeRequests = output.length();

        mockMvc.perform(post("/some-alias"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(jsonPath("$.error").value("Method not allowed"))
                .andExpect(this::hasNoJavaInternals);
        mockMvc.perform(get("/no/such/path").header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not found"))
                .andExpect(this::hasNoJavaInternals);

        verify(urlService, never()).redirectResultUrl(any(), any());
        assertThat(output.toString().substring(outputBeforeRequests))
                .doesNotContain("ERROR")
                .doesNotContain("Unhandled exception")
                .doesNotContain("\tat ")
                .doesNotContain("/no/such/path");
    }

    @Test
    void oversizedBodiesAre413OnEveryUnauthenticatedEndpoint() throws Exception {
        final var registration = padded(REGISTRATION, BODY_LIMIT + 1);
        final var toolsList = padded(TOOLS_LIST, BODY_LIMIT + 1);

        for (final var request : List.of(jsonPost("/oauth2/register", registration), jsonPost("/mcp", toolsList))) {
            mockMvc.perform(request.with(from(CLIENT_IP)))
                    .andExpect(status().isContentTooLarge())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(content().string(REQUEST_TOO_LARGE));
        }

        assertThat(redis.isEmpty()).isTrue();
    }

    @Test
    void oversizedBodiesOfUnknownLengthNeverReachTheHandlers() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        final var registration = padded(REGISTRATION, BODY_LIMIT + 1);
        final var toolsList = padded(TOOLS_LIST, BODY_LIMIT + 1);

        mockMvc.perform(jsonPost("/oauth2/register", registration).with(unknownLength()))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().string(REQUEST_TOO_LARGE));
        mockMvc.perform(jsonPost("/mcp", toolsList).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).with(unknownLength()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid message format")));
        mockMvc.perform(jsonPost("/mcp", toolsList).with(unknownLength()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bodiesAtTheLimitStillWork() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        final var registration = padded(REGISTRATION, BODY_LIMIT);
        final var toolsList = padded(TOOLS_LIST, BODY_LIMIT);

        mockMvc.perform(jsonPost("/oauth2/register", registration).with(from(CLIENT_IP)))
                .andExpect(status().isCreated());
        mockMvc.perform(jsonPost("/oauth2/register", registration).with(from(CLIENT_IP)).with(unknownLength()))
                .andExpect(status().isCreated());
        mockMvc.perform(jsonPost("/mcp", toolsList).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools").exists());
        mockMvc.perform(jsonPost("/mcp", toolsList).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).with(unknownLength()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools").exists());
    }

    private String tokenOf(final Caller caller) throws Exception {
        return switch (caller) {
            case NOBODY -> "";
            case MCP_ACCESS_TOKEN -> accessTokens.issue(EMAIL, RESOURCE);
            case APP_JWT -> appJwt();
        };
    }

    private String appJwt() {
        final var appJwt = tokenProvider.createToken(UsernamePasswordAuthenticationToken.authenticated(
                EMAIL, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThat(tokenProvider.validateToken(appJwt)).isTrue();

        return appJwt;
    }

    private void hasNoJavaInternals(final MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("stackTrace")
                .doesNotContain("\"cause\"")
                .doesNotContain("Exception")
                .doesNotContain("at cc.")
                .doesNotContain("at org.")
                .doesNotContain("at io.")
                .doesNotContain("at java.")
                .doesNotContain("io.modelcontextprotocol")
                .doesNotContain("org.springframework")
                .doesNotContain("java.lang");
    }

    private static MockHttpServletRequestBuilder removedServerRequest(final HttpMethod method, final String token) {
        final var removedServerRequest = request(method, REMOVED_ANONYMOUS_MCP)
                .header(HttpHeaders.ACCEPT, MCP_ACCEPT)
                .contentType(APPLICATION_JSON)
                .content(INITIALIZE);

        return token.isEmpty() ? removedServerRequest : removedServerRequest.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder jsonPost(final String path, final String body) {
        return post(path)
                .header(HttpHeaders.ACCEPT, MCP_ACCEPT)
                .contentType(APPLICATION_JSON)
                .content(body);
    }

    private static String padded(final String json, final int length) {
        return json + " ".repeat(length - json.length());
    }

    private static RequestPostProcessor unknownLength() {
        return request -> {
            final var chunked = spy(request);
            doReturn(-1).when(chunked).getContentLength();
            doReturn(-1L).when(chunked).getContentLengthLong();
            return chunked;
        };
    }

    private static RequestPostProcessor from(final String clientIp) {
        return request -> {
            request.setRemoteAddr(clientIp);
            return request;
        };
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (final JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private enum Caller {
        NOBODY, MCP_ACCESS_TOKEN, APP_JWT
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
            McpMessageBodyLogFilter.class,
            JwtFilter.class,
            TokenProvider.class,
            ClientIpResolver.class,
            ClickRequestResolver.class,
            UrlRedirectController.class,
            ClockConfiguration.class,
            McpUserResolver.class,
            LinkTools.class,
            ModerationTools.class,
            RedisFixedWindowRateLimiter.class
    })
    static class TestApplication {
    }

}
