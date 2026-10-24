package cc.hrva.urlshortener.mcp;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.controller.UrlRedirectController;
import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.LinkSummary;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
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
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = McpEndpointSecurityTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
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
@ExtendWith(OutputCaptureExtension.class)
class McpEndpointSecurityTest {

    private static final String ISSUER = "https://hrva.cc";
    private static final String RESOURCE = ISSUER + "/mcp";
    private static final String RESOURCE_METADATA = ISSUER + "/.well-known/oauth-protected-resource/mcp";
    private static final String EMAIL = "user@localhost";
    private static final String MCP_ACCEPT = "application/json, text/event-stream";
    private static final String REGISTRATION = """
            {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none"}""";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";
    private static final String LIST_LINKS_CALL = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_links","arguments":{}}}""";
    private static final String SHORTEN_URL_CALL = """
            {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"shorten_url","arguments":{"url":"https://secret-destination.example/path","alias":"secret-alias","visitLimit":5,"expiresAt":"2030-06-30T21:59:00Z","tags":["secret-tag"]}}}""";
    private static final String LINK_STATS_CALL = """
            {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"link_stats","arguments":{"link":"https://hrva.cc/secret-alias"}}}""";
    private static final String BLOCK_DOMAIN_CALL = """
            {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"block_domain","arguments":{"domain":"jofogas.cards","reason":"lookalike"}}}""";
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":3,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0.0"}}}""";
    private static final String RESOURCES_LIST = """
            {"jsonrpc":"2.0","id":7,"method":"resources/list","params":{}}""";
    private static final String RESOURCE_TEMPLATES_LIST = """
            {"jsonrpc":"2.0","id":8,"method":"resources/templates/list","params":{}}""";
    private static final String PROMPTS_LIST = """
            {"jsonrpc":"2.0","id":9,"method":"prompts/list","params":{}}""";
    private static final String ADMINS_ONLY = "Moderation tools are for hrva.cc admins only.";
    private static final String GENERIC_TOOL_ERROR = "Something went wrong on hrva.cc. Try again in a moment.";
    private static final String INFRASTRUCTURE_HOST = "db.internal.example:5432";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private OAuth2AuthorizationService authorizationService;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AnalyticsService analyticsService;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private RateLimiter rateLimiter;

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

    private McpAccessTokens accessTokens;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        accessTokens = new McpAccessTokens(mockMvc);
    }

    @Test
    void mcpWithoutTokenIs401WithResourceMetadata() throws Exception {
        mockMvc.perform(mcpRequest(TOOLS_LIST)
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("resource_metadata=\"" + RESOURCE_METADATA + "\"")))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, not(containsString("evil.example"))));

        verifyNoInteractions(urlService, userService);
    }

    @Test
    void protectedResourceMetadataNamesResourceAndIssuer() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value(RESOURCE))
                .andExpect(jsonPath("$.authorization_servers.length()").value(1))
                .andExpect(jsonPath("$.authorization_servers[0]").value(ISSUER))
                .andExpect(jsonPath("$.resource_name").value("hrva.cc"))
                .andExpect(jsonPath("$.bearer_methods_supported", hasItem("header")))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false))
                .andExpect(content().string(not(containsString("evil.example"))));
    }

    @Test
    void tokenWithWrongAudienceIs401() throws Exception {
        final var clientId = accessTokens.register(REGISTRATION);
        final var issued = (String) accessTokens.issueTokens(clientId, EMAIL, RESOURCE).get("access_token");
        final var wrongAudience = replaceAccessToken(issued, clientId);

        mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAudience))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error_description=\"The aud claim is not valid\"")));

        final var rightAudience = replaceAccessToken(wrongAudience, RESOURCE);

        mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + rightAudience))
                .andExpect(status().isOk());
    }

    @Test
    void tokenForRemovedAuthorizationIs401() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        final var authorization = authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
        assertThat(authorization).isNotNull();
        authorizationService.remove(authorization);

        mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")));
    }

    @Test
    void databaseFailureWhileCheckingTheTokenIs401WithoutInfrastructureDetails(final CapturedOutput output) throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        doThrow(new DataAccessResourceFailureException("Connection to " + INFRASTRUCTURE_HOST + " refused"))
                .when(authorizationService).findByToken(token, OAuth2TokenType.ACCESS_TOKEN);

        try (final var httpClient = HttpClient.newHttpClient()) {
            final var response = httpClient.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                    .header(HttpHeaders.ACCEPT, MCP_ACCEPT)
                    .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(TOOLS_LIST))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertThat(response.body()).doesNotContain(INFRASTRUCTURE_HOST);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).hasValueSatisfying(challenge -> assertThat(challenge)
                    .contains("error=\"invalid_token\"")
                    .contains("resource_metadata=\"" + RESOURCE_METADATA + "\"")
                    .doesNotContain(INFRASTRUCTURE_HOST));
        }
        assertThat(output.getAll())
                .contains("Could not check whether an MCP access token is still authorized")
                .doesNotContain(INFRASTRUCTURE_HOST)
                .doesNotContain(token);
        verifyNoInteractions(urlService, userService);
    }

    @Test
    void validTokenListsTools() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);

        final var body = mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[*].name", hasItems("list_links", "shorten_url", "link_stats")))
                .andExpect(jsonPath("$.result.tools[*].name", hasItems("moderation_summary", "review_queue", "possible_scammers", "check_url",
                        "block_link", "block_domain", "block_user", "delete_link")))
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("allow_domain"))))
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("trust_domain"))))
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("resolve_review"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        final List<Map<String, Object>> inputProperties = JsonPath.read(body, "$.result.tools[?(@.name == 'list_links')].inputSchema.properties");
        assertThat(inputProperties).singleElement()
                .satisfies(properties -> assertThat(properties).containsOnlyKeys("search", "tag", "page"));
        final List<Map<String, Object>> shortenProperties = JsonPath.read(body, "$.result.tools[?(@.name == 'shorten_url')].inputSchema.properties");
        assertThat(shortenProperties).singleElement()
                .satisfies(properties -> assertThat(properties).containsOnlyKeys("url", "alias", "visitLimit", "expiresAt", "tags"));
        final List<List<String>> shortenRequired = JsonPath.read(body, "$.result.tools[?(@.name == 'shorten_url')].inputSchema.required");
        assertThat(shortenRequired).singleElement().satisfies(required -> assertThat(required).containsExactly("url"));
        final List<Map<String, Object>> statsProperties = JsonPath.read(body, "$.result.tools[?(@.name == 'link_stats')].inputSchema.properties");
        assertThat(statsProperties).singleElement()
                .satisfies(properties -> assertThat(properties).containsOnlyKeys("link", "days"));
        final List<List<String>> statsRequired = JsonPath.read(body, "$.result.tools[?(@.name == 'link_stats')].inputSchema.required");
        assertThat(statsRequired).singleElement().satisfies(required -> assertThat(required).containsExactly("link"));
    }

    @Test
    void moderationToolsAreListedAsAdminOnlyWithDestructiveHintsOnTheBlocks() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);

        final var body = mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        final List<String> descriptions = JsonPath.read(body, "$.result.tools[?(@.name in ['moderation_summary', 'review_queue', "
                + "'possible_scammers', 'check_url', 'block_link', 'block_domain', 'block_user', 'delete_link'])].description");
        assertThat(descriptions).hasSize(8).allSatisfy(description -> assertThat(description).startsWith("Admin only."));
        final List<String> destructive = JsonPath.read(body, "$.result.tools[?(@.annotations.destructiveHint == true)].name");
        assertThat(destructive).containsExactlyInAnyOrder("block_link", "block_domain", "block_user", "delete_link");
        final List<String> readOnly = JsonPath.read(body, "$.result.tools[?(@.annotations.readOnlyHint == true)].name");
        assertThat(readOnly).contains("moderation_summary", "review_queue", "possible_scammers", "check_url")
                .doesNotContain("block_link", "block_domain", "block_user", "delete_link");
        final List<Map<String, Object>> blockDomainProperties =
                JsonPath.read(body, "$.result.tools[?(@.name == 'block_domain')].inputSchema.properties");
        assertThat(blockDomainProperties).singleElement()
                .satisfies(properties -> assertThat(properties).containsOnlyKeys("domain", "reason"));
        final List<Map<String, Object>> blockUserProperties =
                JsonPath.read(body, "$.result.tools[?(@.name == 'block_user')].inputSchema.properties");
        assertThat(blockUserProperties).singleElement()
                .satisfies(properties -> assertThat(properties).containsOnlyKeys("userId"));
    }

    @Test
    void nonAdminCallingAModerationToolIsRefusedAndNothingIsBlocked() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(User.builder().id(7L).email(EMAIL).active(true).build());

        mockMvc.perform(mcpRequest(BLOCK_DOMAIN_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content.length()").value(1))
                .andExpect(jsonPath("$.result.content[0].text").value(ADMINS_ONLY));

        verify(userRepository).existsByIdAndAuthoritiesName(7L, "ROLE_ADMIN");
        verifyNoInteractions(moderationService);
    }

    @Test
    void adminBlocksADomainAsTheTokenSubjectViaMcp(final CapturedOutput output) throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        final var callerDuringBlock = new AtomicReference<String>();
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(User.builder().id(7L).email(EMAIL).active(true).build());
        when(userRepository.existsByIdAndAuthoritiesName(7L, "ROLE_ADMIN")).thenReturn(true);
        when(urlRepository.findFlaggedOrBlockedDestinationsMentioning("jofogas.cards", "jofogas.cards"))
                .thenReturn(List.of("https://login.jofogas.cards/pay"));
        when(moderationService.blockDomain("jofogas.cards", "lookalike", EMAIL, "mcp", 50)).thenAnswer(invocation -> {
            callerDuringBlock.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return 2;
        });

        final var outputBeforeCall = output.length();
        mockMvc.perform(mcpRequest(BLOCK_DOMAIN_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text").value("Blocked jofogas.cards and its subdomains. Links blocked now: 2."));

        assertThat(callerDuringBlock.get()).isEqualTo(EMAIL);
        assertThat(output.toString().substring(outputBeforeCall))
                .contains("MCP block_domain adminId=7 links=2")
                .doesNotContain(EMAIL);
    }

    @Test
    void shortenUrlCreatesTheLinkAsTheTokenSubject(final CapturedOutput output) throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        final var callerDuringCreate = new AtomicReference<String>();
        final var request = new AtomicReference<CreateUrlDto>();
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(User.builder().id(7L).email(EMAIL).active(true).build());
        when(urlService.saveUrlRouting(any(), any())).thenAnswer(invocation -> {
            callerDuringCreate.set(SecurityContextHolder.getContext().getAuthentication().getName());
            request.set(invocation.getArgument(0));
            return new UrlResponse(11L, "https://secret-destination.example/path", "secret-alias", LocalDateTime.now(), null, 0L, 5L,
                    true, "ACTIVE", Set.of("ai", "secret-tag"), false, EMAIL);
        });

        final var outputBeforeCall = output.length();
        mockMvc.perform(mcpRequest(SHORTEN_URL_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString(ISSUER + "/secret-alias")));

        assertThat(callerDuringCreate.get()).isEqualTo(EMAIL);
        assertThat(request.get().getShortUrl()).isEqualTo("secret-alias");
        assertThat(request.get().getVisitLimit()).isEqualTo(5L);
        assertThat(request.get().getExpirationDate()).isNotNull();
        assertThat(request.get().getTags()).containsExactly("ai", "secret-tag");
        assertThat(output.toString().substring(outputBeforeCall))
                .contains("MCP shorten_url userId=7 urlId=11")
                .doesNotContain("secret-destination.example")
                .doesNotContain("secret-alias")
                .doesNotContain("secret-tag")
                .doesNotContain(EMAIL);
    }

    @Test
    void linkStatsReadsOwnerOnlyStatsAsTheTokenSubject(final CapturedOutput output) throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        final var callerDuringStats = new AtomicReference<String>();
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(User.builder().id(7L).email(EMAIL).active(true).build());
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(new PageImpl<>(List.of(link("secret-alias", "https://example.com/a"))));
        when(analyticsService.getUrlAnalyticsForOwner(1L, null)).thenAnswer(invocation -> {
            callerDuringStats.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return new UrlAnalyticsDto(new LinkSummary(1L, "secret-alias", "example.com", "ACTIVE", false, LocalDateTime.now(), null, 3L),
                    30, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 24), new AnalyticsTotals(2L, 1L, 0L), List.of(),
                    new Breakdowns(List.of(), List.of(), List.of(), List.of(), List.of()));
        });

        final var outputBeforeCall = output.length();
        mockMvc.perform(mcpRequest(LINK_STATS_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString(
                        "Unique visitors are counted per day, so one person on two different days counts twice.")));

        verify(analyticsService, never()).getUrlAnalytics(any(), any());
        assertThat(callerDuringStats.get()).isEqualTo(EMAIL);
        assertThat(output.toString().substring(outputBeforeCall))
                .contains("MCP link_stats userId=7 urlId=1")
                .doesNotContain("secret-alias");
    }

    @Test
    void listLinksRunsAsTheTokenSubject(final CapturedOutput output) throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        final var callerDuringLookup = new AtomicReference<String>();
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(User.builder().id(7L).email(EMAIL).active(true).build());
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenAnswer(invocation -> {
            callerDuringLookup.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return new PageImpl<>(List.of(link("abc12", "https://example.com/a")));
        });

        final var outputBeforeCall = output.length();
        mockMvc.perform(mcpRequest(LIST_LINKS_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString(ISSUER + "/abc12")));

        verify(userService).fetchActiveUserByEmail(EMAIL);
        verify(userService, never()).fetchUserFromEmail(any());
        assertThat(callerDuringLookup.get()).isEqualTo(EMAIL);
        assertThat(output.toString().substring(outputBeforeCall))
                .contains("MCP list_links userId=7")
                .doesNotContain(EMAIL);
    }

    @Test
    void toolFailureReturnsGenericErrorWithoutInternals() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(User.builder().id(7L).email(EMAIL).active(true).build());
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenThrow(new IllegalStateException("internal-detail-from-the-database"));

        mockMvc.perform(mcpRequest(LIST_LINKS_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content.length()").value(1))
                .andExpect(jsonPath("$.result.content[0].text").value(GENERIC_TOOL_ERROR))
                .andExpect(content().string(not(containsString("internal-detail-from-the-database"))));
    }

    @Test
    void initializeAdvertisesToolsAndEmptyResourcesAndPrompts() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);

        final var body = mockMvc.perform(mcpRequest(INITIALIZE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.serverInfo.name").value("hrva.cc"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        final Map<String, Object> capabilities = JsonPath.read(body, "$.result.capabilities");
        assertThat(capabilities).containsOnlyKeys("tools", "resources", "prompts");
    }

    @Test
    void modernDiscoveryProbeIsChallengedThenAnsweredAsALegacyServer() throws Exception {
        mockMvc.perform(modernDiscoveryProbe())
                .andExpect(status().isUnauthorized());

        final var token = accessTokens.issue(EMAIL, RESOURCE);
        mockMvc.perform(modernDiscoveryProbe().header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32601));
        mockMvc.perform(mcpRequest(INITIALIZE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.serverInfo.name").value("hrva.cc"));
    }

    @Test
    void unknownMethodIsAJsonRpcErrorNotAServerError() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);

        mockMvc.perform(mcpRequest("""
                        {"jsonrpc":"2.0","id":11,"method":"logging/setLevel","params":{"level":"info"}}""")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void clientDiscoveryListsAreEmptyNotErrors() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);

        mockMvc.perform(mcpRequest(RESOURCES_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.resources").isEmpty());
        mockMvc.perform(mcpRequest(RESOURCE_TEMPLATES_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.resourceTemplates").isEmpty());
        mockMvc.perform(mcpRequest(PROMPTS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.prompts").isEmpty());
    }

    @Test
    void malformedMcpBodyIsNotLogged(final CapturedOutput output) throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        final var notJsonRpc = """
                {"jsonrpc":"2.0","params":{"name":"list_links","arguments":{"search":"https://secret-argument.example/path"}}}""";
        final var truncated = """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_links","arguments":{"search":"https://secret-argument.example/path""";

        for (final var invalidBody : List.of(notJsonRpc, truncated)) {
            mockMvc.perform(mcpRequest(invalidBody).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        assertThat(output)
                .doesNotContain("secret-argument.example")
                .doesNotContain("Failed to deserialize message")
                .contains("Rejected an MCP message that is not valid JSON-RPC");
    }

    @Test
    void getAndPostMcpNeverReachRedirectController() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        when(urlService.redirectResultUrl(eq("abc12"), any())).thenReturn(new RedirectView("https://example.com/a"));

        mockMvc.perform(get("/mcp"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(mcpRequest(TOOLS_LIST))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        verify(urlService, never()).redirectResultUrl(any(), any());

        mockMvc.perform(get("/abc12"))
                .andExpect(status().isFound());
        verify(urlService).redirectResultUrl(eq("abc12"), any());
    }

    @Test
    void mcpErrorBodyHasNoStackTrace() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);

        for (final var invalidBody : List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":", "{\"unexpected\":true}")) {
            mockMvc.perform(mcpRequest(invalidBody).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("Invalid message format")))
                    .andExpect(content().string(not(containsString("stackTrace"))))
                    .andExpect(content().string(not(containsString("at cc."))))
                    .andExpect(content().string(not(containsString("\"cause\""))));
        }
    }

    private String replaceAccessToken(final String accessToken, final String audience) {
        final var authorization = authorizationService.findByToken(accessToken, OAuth2TokenType.ACCESS_TOKEN);
        assertThat(authorization).isNotNull();
        final var issuedAt = Instant.now();
        final var jwt = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(SIGNING_KEY))).encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(EMAIL)
                .audience(new ArrayList<>(List.of(audience)))
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofHours(1)))
                .id(UUID.randomUUID().toString())
                .build()));
        final var replacement = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt());
        authorizationService.save(OAuth2Authorization.from(authorization)
                .token(replacement, metadata -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, jwt.getClaims()))
                .build());

        return jwt.getTokenValue();
    }

    private static MockHttpServletRequestBuilder modernDiscoveryProbe() {
        return mcpRequest("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}""")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "server/discover");
    }

    private static MockHttpServletRequestBuilder mcpRequest(final String body) {
        return post("/mcp")
                .header(HttpHeaders.ACCEPT, MCP_ACCEPT)
                .contentType(APPLICATION_JSON)
                .content(body);
    }

    private static UrlResponse link(final String alias, final String destination) {
        return new UrlResponse(1L, destination, alias, LocalDateTime.now(), null, 3L, null, true, "ACTIVE", Set.of(), false, EMAIL);
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
            ClientIpResolver.class,
            ClickRequestResolver.class,
            UrlRedirectController.class,
            ClockConfiguration.class,
            McpUserResolver.class,
            LinkTools.class,
            ModerationTools.class
    })
    static class TestApplication {
    }

}
