package cc.hrva.urlshortener.mcp.anon;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.controller.UrlRedirectController;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.mcp.McpAccessTokens;
import cc.hrva.urlshortener.mcp.McpMessageBodyLogFilter;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.mcp.tools.LinkTools;
import cc.hrva.urlshortener.mcp.tools.McpUserResolver;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AnonymousMcpTest.TestApplication.class,
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
class AnonymousMcpTest {

    private static final String ISSUER = "https://hrva.cc";
    private static final String RESOURCE = ISSUER + "/mcp";
    private static final String ANONYMOUS_MCP = "/mcp/anon";
    private static final String EMAIL = "user@localhost";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String OTHER_CLIENT_IP = "198.51.100.23";
    private static final String PROXY_IP = "10.0.0.1";
    private static final String CF_CONNECTING_IP = "CF-Connecting-IP";
    private static final String DESTINATION = "https://secret-destination.example/path";
    private static final String RATE_LIMIT_KEY_PATTERN = "rate-limit:mcp-anon:[0-9a-f]{64}";
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(60);
    private static final String MCP_ACCEPT = "application/json, text/event-stream";
    private static final String REFUSAL = "Too many anonymous links from this network. Try again in a minute, "
            + "or connect https://hrva.cc/mcp to use your account.";
    private static final String GENERIC_TOOL_ERROR = "Something went wrong on hrva.cc. Try again in a moment.";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":3,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0.0"}}}""";
    private static final String SHORTEN_URL_CALL = """
            {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"shorten_url","arguments":{"url":"https://secret-destination.example/path","alias":"chosen-alias","tags":["chosen-tag"]}}}""";
    private static final List<String> INVALID_SHORTEN_URL_CALLS = List.of(
            """
                    {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"shorten_url","arguments":{}}}""",
            """
                    {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"shorten_url","arguments":{"url":"  "}}}""",
            """
                    {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"shorten_url","arguments":{"url":42}}}""");
    private static final String LIST_LINKS_CALL = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_links","arguments":{}}}""";
    private static final String LINK_STATS_CALL = """
            {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"link_stats","arguments":{"link":"https://hrva.cc/abc12"}}}""";
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

    private final InMemoryRedis redis = new InMemoryRedis();
    private final List<Authentication> callersDuringCreate = new CopyOnWriteArrayList<>();
    private final List<CreateUrlDto> createRequests = new CopyOnWriteArrayList<>();
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
        when(urlService.saveUrlRouting(any())).thenAnswer(invocation -> {
            callersDuringCreate.add(SecurityContextHolder.getContext().getAuthentication());
            createRequests.add(invocation.getArgument(0));
            return new UrlResponse(11L, DESTINATION, "abc12", LocalDateTime.now(), null, 0L, null,
                    true, "ACTIVE", Set.of("claude"), false, null);
        });
    }

    @Test
    void anonListsOnlyShortenUrl() throws Exception {
        final var body = mockMvc.perform(anonymousMcpRequest(TOOLS_LIST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(1))
                .andExpect(jsonPath("$.result.tools[0].name").value("shorten_url"))
                .andExpect(jsonPath("$.result.tools[0].description", containsString("anonymous")))
                .andExpect(jsonPath("$.result.tools[0].description", containsString("https://hrva.cc/mcp")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        final Map<String, Object> properties = JsonPath.read(body, "$.result.tools[0].inputSchema.properties");
        assertThat(properties).containsOnlyKeys("url");
        final List<String> required = JsonPath.read(body, "$.result.tools[0].inputSchema.required");
        assertThat(required).containsExactly("url");

        final var initialize = mockMvc.perform(anonymousMcpRequest(INITIALIZE))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final Map<String, Object> capabilities = JsonPath.read(initialize, "$.result.capabilities");
        assertThat(capabilities).containsOnlyKeys("tools");

        final var token = accessTokens.issue(EMAIL, RESOURCE);
        final var signedIn = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.ACCEPT, MCP_ACCEPT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(TOOLS_LIST))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final List<String> signedInTools = JsonPath.read(signedIn, "$.result.tools[*].name");
        assertThat(signedInTools).containsExactlyInAnyOrder("list_links", "shorten_url", "link_stats");
        final List<Map<String, Object>> signedInShortenProperties =
                JsonPath.read(signedIn, "$.result.tools[?(@.name == 'shorten_url')].inputSchema.properties");
        assertThat(signedInShortenProperties).singleElement()
                .satisfies(signedInProperties -> assertThat(signedInProperties).containsOnlyKeys("url", "alias", "visitLimit", "expiresAt", "tags"));
    }

    @Test
    void anonShortenCreatesAnonymousLink(final CapturedOutput output) throws Exception {
        final var outputBeforeCall = output.length();

        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(CLIENT_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString(ISSUER + "/abc12")));

        assertThat(callersDuringCreate).singleElement().isInstanceOf(AnonymousAuthenticationToken.class);
        assertThat(createRequests).singleElement().satisfies(request -> {
            assertThat(request.getLongUrl()).isEqualTo(DESTINATION);
            assertThat(request.getShortUrl()).isNull();
            assertThat(request.getVisitLimit()).isNull();
            assertThat(request.getExpirationDate()).isNull();
            assertThat(request.getPassword()).isNull();
            assertThat(request.getTags()).containsExactly("claude");
        });
        assertThat(redis.keys()).singleElement().satisfies(key -> {
            assertThat(key).matches(RATE_LIMIT_KEY_PATTERN);
            assertThat(key).doesNotContain(CLIENT_IP);
        });
        assertThat(redis.timeToLiveOfKeyContaining("rate-limit:mcp-anon:")).isEqualTo(RATE_LIMIT_WINDOW);
        assertThat(output.toString().substring(outputBeforeCall))
                .contains("MCP anonymous shorten_url urlId=11")
                .doesNotContain(CLIENT_IP)
                .doesNotContain("secret-destination.example")
                .doesNotContain("chosen-alias")
                .doesNotContain("chosen-tag");
    }

    @Test
    void anonIgnoresBearerToken() throws Exception {
        final var mcpAccessToken = accessTokens.issue(EMAIL, RESOURCE);
        final var appJwt = tokenProvider.createToken(UsernamePasswordAuthenticationToken.authenticated(
                EMAIL, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThat(tokenProvider.validateToken(appJwt)).isTrue();
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.ACCEPT, MCP_ACCEPT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + mcpAccessToken)
                        .contentType(APPLICATION_JSON)
                        .content(TOOLS_LIST))
                .andExpect(status().isOk());

        for (final var token : List.of(mcpAccessToken, appJwt, "not-a-token")) {
            mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .with(from(CLIENT_IP)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.isError").value(false))
                    .andExpect(jsonPath("$.result.content[0].text", containsString(ISSUER + "/abc12")));
        }

        assertThat(callersDuringCreate).hasSize(3)
                .allSatisfy(caller -> assertThat(caller).isInstanceOf(AnonymousAuthenticationToken.class));
        verify(userService, never()).fetchActiveUserByEmail(any());
    }

    @Test
    void eleventhCallInWindowIsRefusedWithExactText(final CapturedOutput output) throws Exception {
        final var outputBeforeCalls = output.length();

        for (int call = 1; call <= 10; call++) {
            mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(CLIENT_IP)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.isError").value(false));
        }

        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(CLIENT_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content.length()").value(1))
                .andExpect(jsonPath("$.result.content[0].text").value(REFUSAL));
        verify(urlService, times(10)).saveUrlRouting(any());

        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(OTHER_CLIENT_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));

        redis.expireKeysContaining("rate-limit:mcp-anon:");
        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(CLIENT_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));

        verify(urlService, times(12)).saveUrlRouting(any());
        assertThat(output.toString().substring(outputBeforeCalls))
                .contains("MCP anonymous shorten_url refused by the rate limit")
                .doesNotContain(CLIENT_IP)
                .doesNotContain(OTHER_CLIENT_IP);
    }

    @Test
    void rateLimitCountsTheCloudflareClientNotTheProxy() throws Exception {
        for (int call = 1; call <= 10; call++) {
            mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(PROXY_IP)).header(CF_CONNECTING_IP, CLIENT_IP))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.isError").value(false));
        }

        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(PROXY_IP)).header(CF_CONNECTING_IP, CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value(REFUSAL));
        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(PROXY_IP)).header(CF_CONNECTING_IP, OTHER_CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));

        verify(urlService, times(11)).saveUrlRouting(any());
        assertThat(redis.keys()).hasSize(2).allSatisfy(key -> assertThat(key)
                .matches(RATE_LIMIT_KEY_PATTERN)
                .doesNotContain(CLIENT_IP)
                .doesNotContain(OTHER_CLIENT_IP)
                .doesNotContain(PROXY_IP));
    }

    @Test
    void everyShortenAttemptCountsEvenWhenItFailsValidation() throws Exception {
        for (int call = 0; call < 10; call++) {
            mockMvc.perform(anonymousMcpRequest(INVALID_SHORTEN_URL_CALLS.get(call % INVALID_SHORTEN_URL_CALLS.size())).with(from(CLIENT_IP)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.isError").value(true))
                    .andExpect(jsonPath("$.result.content[0].text").value("Long URL is required"));
        }

        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(CLIENT_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value(REFUSAL));

        verify(urlService, never()).saveUrlRouting(any());
    }

    @Test
    void initializeAndToolsListAreNotRateLimited() throws Exception {
        for (int call = 0; call < 12; call++) {
            mockMvc.perform(anonymousMcpRequest(INITIALIZE).with(from(CLIENT_IP)))
                    .andExpect(status().isOk());
            mockMvc.perform(anonymousMcpRequest(TOOLS_LIST).with(from(CLIENT_IP)))
                    .andExpect(status().isOk());
        }

        assertThat(redis.isEmpty()).isTrue();
        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(CLIENT_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));
    }

    @Test
    void unavailableRedisFailsClosedWithGenericError(final CapturedOutput output) throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to redis.internal/10.0.0.5:6379"));
        final var outputBeforeCall = output.length();

        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(CLIENT_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content.length()").value(1))
                .andExpect(jsonPath("$.result.content[0].text").value(GENERIC_TOOL_ERROR))
                .andExpect(content().string(not(containsString("redis.internal"))));

        verify(urlService, never()).saveUrlRouting(any());
        final var logged = output.toString().substring(outputBeforeCall);
        assertThat(logged.lines())
                .anySatisfy(line -> assertThat(line).contains("WARN").contains("Rate limit check failed"));
        assertThat(logged)
                .doesNotContain(CLIENT_IP)
                .doesNotContain("secret-destination.example");
    }

    @Test
    void serviceFailuresBecomeToolErrors() throws Exception {
        when(urlService.saveUrlRouting(any()))
                .thenThrow(new ApiException("Sign in to create short URLs"))
                .thenThrow(new IllegalStateException("internal-detail-from-the-database"));

        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(CLIENT_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value("Sign in to create short URLs"));
        mockMvc.perform(anonymousMcpRequest(SHORTEN_URL_CALL).with(from(CLIENT_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value(GENERIC_TOOL_ERROR))
                .andExpect(content().string(not(containsString("internal-detail-from-the-database"))));
    }

    @Test
    void signedInToolsNotReachableOnAnon() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);

        for (final var signedInCall : List.of(LIST_LINKS_CALL, LINK_STATS_CALL)) {
            mockMvc.perform(anonymousMcpRequest(signedInCall).with(from(CLIENT_IP)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").doesNotExist())
                    .andExpect(jsonPath("$.error.message", containsString("Unknown tool")));
            mockMvc.perform(anonymousMcpRequest(signedInCall)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .with(from(CLIENT_IP)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").doesNotExist())
                    .andExpect(jsonPath("$.error.message", containsString("Unknown tool")));
        }

        verify(urlService, never()).getAllMyUrls(any(), any(), any());
        verifyNoInteractions(analyticsService);
        verify(userService, never()).fetchActiveUserByEmail(any());
    }

    @Test
    void anonNeverReachesRedirectController() throws Exception {
        when(urlService.redirectResultUrl(any(), any())).thenReturn(new RedirectView("https://example.com/a"));

        mockMvc.perform(get(ANONYMOUS_MCP))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(anonymousMcpRequest(TOOLS_LIST))
                .andExpect(status().isOk());
        for (final var deleteRequest : List.of(delete(ANONYMOUS_MCP), delete(ANONYMOUS_MCP).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))) {
            final var response = mockMvc.perform(deleteRequest)
                    .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                    .andExpect(this::hasNoJavaInternals)
                    .andReturn()
                    .getResponse();
            assertThat(response.getStatus()).isNotIn(301, 302, 303, 307, 308, 401);
        }

        verify(urlService, never()).redirectResultUrl(any(), any());

        mockMvc.perform(get("/abc12"))
                .andExpect(status().isFound());
        verify(urlService).redirectResultUrl(eq("abc12"), any());
    }

    @Test
    void unsupportedMethodsAre405AndUnknownPathsAre404WithoutErrorLogs(final CapturedOutput output) throws Exception {
        final var appJwt = tokenProvider.createToken(UsernamePasswordAuthenticationToken.authenticated(
                EMAIL, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        final var outputBeforeRequests = output.length();

        mockMvc.perform(delete(ANONYMOUS_MCP))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")))
                .andExpect(jsonPath("$.error").value("Method not allowed"))
                .andExpect(this::hasNoJavaInternals);
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
    void malformedBodiesAndUnknownMethodsReturnNoStackTrace(final CapturedOutput output) throws Exception {
        final var truncated = """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"shorten_url","arguments":{"url":"https://secret-argument.example/path""";
        final var invalidBodies = List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":", "{\"unexpected\":true}", "not json at all", truncated);

        for (final var invalidBody : invalidBodies) {
            mockMvc.perform(anonymousMcpRequest(invalidBody).with(from(CLIENT_IP)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("Invalid message format")))
                    .andExpect(this::hasNoJavaInternals);
        }

        for (final var unknownMethod : List.of("resources/list", "no/such/method")) {
            mockMvc.perform(anonymousMcpRequest("""
                            {"jsonrpc":"2.0","id":9,"method":"%s","params":{}}""".formatted(unknownMethod)).with(from(CLIENT_IP)))
                    .andExpect(this::hasNoJavaInternals);
        }

        assertThat(output)
                .doesNotContain("secret-argument.example")
                .doesNotContain("Failed to deserialize message")
                .doesNotContain(CLIENT_IP);
        assertThat(redis.isEmpty()).isTrue();
        verifyNoInteractions(urlService);
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

    private static MockHttpServletRequestBuilder anonymousMcpRequest(final String body) {
        return post(ANONYMOUS_MCP)
                .header(HttpHeaders.ACCEPT, MCP_ACCEPT)
                .contentType(APPLICATION_JSON)
                .content(body);
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
            UrlRedirectController.class,
            McpUserResolver.class,
            LinkTools.class,
            AnonymousMcpConfiguration.class,
            AnonymousShortenTool.class,
            RedisFixedWindowRateLimiter.class
    })
    static class TestApplication {
    }

}
