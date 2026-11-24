package cc.hrva.urlshortener.mcp;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.admin.AdminMcpServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.mcp.modern.ModernMcpServers;
import cc.hrva.urlshortener.mcp.tools.LinkTools;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.dnsfilter.DnsFilterService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.settings.DefaultSettingsTestConfiguration;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminMcpEndpointTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost",
                "spring.ai.mcp.server.capabilities.completion=true",
                "spring.ai.mcp.server.instructions=Test instructions for both servers."
        })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AdminMcpEndpointTest {

    private static final String ISSUER = "https://hrva.cc";
    private static final String MCP_RESOURCE = ISSUER + "/mcp";
    private static final String ADMIN_RESOURCE = ISSUER + "/mcp/admin";
    private static final String ADMIN_RESOURCE_METADATA = ISSUER + "/.well-known/oauth-protected-resource/mcp/admin";
    private static final String ADMIN_EMAIL = "admin@localhost";
    private static final String USER_EMAIL = "user@localhost";
    private static final String MCP_ACCEPT = "application/json, text/event-stream";
    private static final List<String> MODERATION_TOOLS = List.of("account_links", "block_domain", "block_link", "block_user", "check_url", "delete_link",
            "moderation_summary", "possible_scammers", "review_queue");
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":3,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0.0"}}}""";
    private static final String RESOURCES_LIST = """
            {"jsonrpc":"2.0","id":7,"method":"resources/list","params":{}}""";
    private static final String PROMPTS_LIST = """
            {"jsonrpc":"2.0","id":9,"method":"prompts/list","params":{}}""";
    private static final String BLOCK_DOMAIN_CALL = """
            {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"block_domain","arguments":{"domain":"jofogas.cards","reason":"lookalike"}}}""";
    private static final String MODERN_VERSION = "2026-07-28";
    private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    private static final String METHOD_HEADER = "Mcp-Method";
    private static final String NAME_HEADER = "Mcp-Name";
    private static final String SERVER_DISCOVER = "server/discover";
    private static final String MCP_REQUESTS = "hrva.mcp.requests";
    private static final String MODERN_DISCOVER = """
            {"jsonrpc":"2.0","id":21,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String MODERN_TOOLS_LIST = """
            {"jsonrpc":"2.0","id":22,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String MODERN_BLOCK_DOMAIN_CALL = """
            {"jsonrpc":"2.0","id":23,"method":"tools/call","params":{"name":"block_domain","arguments":{"domain":"jofogas.cards","reason":"lookalike"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String LEGACY_DISCOVER_ANSWER = """
            {"jsonrpc":"2.0","id":null,"error":{"code":-32601,"message":"Method not found: server/discover. This server implements MCP 2025-11-25; send initialize."}}""";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

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
    private OAuth2LoginUserService oAuth2LoginUserService;

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
    private DnsFilterService dnsFilterService;

    @MockitoBean
    private BlocklistSourceService blocklistSourceService;

    @MockitoBean
    private UrlRepository urlRepository;

    @MockitoBean
    private LinkReviewRepository linkReviewRepository;

    @MockitoBean
    private CustomDomainRepository customDomainRepository;

    @MockitoBean
    private BioPageRepository bioPageRepository;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private MeterRegistry meterRegistry;

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
    void mcpListsOnlyTheLinkTools() throws Exception {
        signedIn(admin(true));
        final var token = accessTokens.issue(ADMIN_EMAIL, MCP_RESOURCE);

        final var body = mockMvc.perform(mcpRequest("/mcp", TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        final List<String> names = JsonPath.read(body, "$.result.tools[*].name");
        assertThat(names).containsExactlyInAnyOrder("link_stats", "list_links", "shorten_url");
    }

    @Test
    void adminEndpointListsTheModerationTools() throws Exception {
        signedIn(admin(true));
        final var token = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);

        final var body = mockMvc.perform(mcpRequest("/mcp/admin", TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        final List<String> names = JsonPath.read(body, "$.result.tools[*].name");
        assertThat(names).containsExactlyInAnyOrderElementsOf(MODERATION_TOOLS);
        final List<String> descriptions = JsonPath.read(body, "$.result.tools[*].description");
        assertThat(descriptions).allSatisfy(description -> assertThat(description).startsWith("Admin only."));
        final List<String> destructive = JsonPath.read(body, "$.result.tools[?(@.annotations.destructiveHint == true)].name");
        assertThat(destructive).containsExactlyInAnyOrder("block_link", "block_domain", "block_user", "delete_link");
        final List<String> readOnly = JsonPath.read(body, "$.result.tools[?(@.annotations.readOnlyHint == true)].name");
        assertThat(readOnly).containsExactlyInAnyOrder("moderation_summary", "review_queue", "possible_scammers", "check_url", "account_links");
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
    void adminEndpointSpeaksTheSameJsonRpcAsMcp() throws Exception {
        signedIn(admin(true));
        final var token = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);
        final var mcpToken = accessTokens.issue(ADMIN_EMAIL, MCP_RESOURCE);

        final var body = mockMvc.perform(mcpRequest("/mcp/admin", INITIALIZE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.serverInfo.name").value("hrva.cc"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        final var mcpBody = mockMvc.perform(mcpRequest("/mcp", INITIALIZE).header(HttpHeaders.AUTHORIZATION, "Bearer " + mcpToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final Map<String, Object> capabilities = JsonPath.read(body, "$.result.capabilities");
        final Map<String, Object> mcpCapabilities = JsonPath.read(mcpBody, "$.result.capabilities");
        assertThat(capabilities).containsOnlyKeys("tools", "resources", "prompts", "completions").isEqualTo(mcpCapabilities);
        assertThat((String) JsonPath.read(body, "$.result.instructions"))
                .isEqualTo("Test instructions for both servers.")
                .isEqualTo(JsonPath.read(mcpBody, "$.result.instructions"));

        mockMvc.perform(mcpRequest("/mcp/admin", RESOURCES_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.resources").isEmpty());
        mockMvc.perform(mcpRequest("/mcp/admin", PROMPTS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.prompts").isEmpty());
        mockMvc.perform(modernRequest("/mcp/admin", SERVER_DISCOVER, MODERN_DISCOVER).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.supportedVersions", contains(MODERN_VERSION)))
                .andExpect(jsonPath("$.result.instructions").value("Test instructions for both servers."));
        mockMvc.perform(get("/mcp/admin").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(mcpRequest("/mcp/admin", "x".repeat(McpRequestBodyLimitFilter.MAX_BODY_BYTES + 1)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void anAdminBlocksADomainAsTheTokenSubject(final CapturedOutput output) throws Exception {
        signedIn(admin(true));
        final var token = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);
        final var callerDuringBlock = new AtomicReference<String>();
        when(urlRepository.findFlaggedOrBlockedDestinationsMentioning("jofogas.cards", "jofogas.cards"))
                .thenReturn(List.of("https://login.jofogas.cards/pay"));
        when(moderationService.blockDomain("jofogas.cards", "lookalike", ADMIN_EMAIL, "mcp", 50)).thenAnswer(invocation -> {
            callerDuringBlock.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return 2;
        });

        final var outputBeforeCall = output.length();
        mockMvc.perform(mcpRequest("/mcp/admin", BLOCK_DOMAIN_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text").value("Blocked jofogas.cards and its subdomains. Links blocked now: 2."));

        assertThat(callerDuringBlock.get()).isEqualTo(ADMIN_EMAIL);
        assertThat(output.toString().substring(outputBeforeCall))
                .contains("MCP block_domain adminId=1 links=2")
                .doesNotContain(ADMIN_EMAIL);
    }

    @Test
    void adminEndpointRefusesAUserWith403(final CapturedOutput output) throws Exception {
        final var user = User.builder().id(7L).email(USER_EMAIL).active(true).twoStepEnabled(true).build();
        signedIn(user);
        final var token = accessTokens.issue(USER_EMAIL, ADMIN_RESOURCE);

        final var outputBeforeCall = output.length();
        mockMvc.perform(mcpRequest("/mcp/admin", TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("block_domain"))));
        mockMvc.perform(mcpRequest("/mcp/admin", BLOCK_DOMAIN_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        assertThat(output.toString().substring(outputBeforeCall))
                .contains("MCP admin endpoint refused a non-admin userId=7")
                .doesNotContain(USER_EMAIL);
        verifyNoInteractions(moderationService, reviewQueueService, urlRepository);
    }

    @Test
    void adminEndpointRefusesAnAdminWithoutTwoStepWith403(final CapturedOutput output) throws Exception {
        signedIn(admin(false));
        final var token = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);

        final var outputBeforeCall = output.length();
        mockMvc.perform(mcpRequest("/mcp/admin", BLOCK_DOMAIN_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        assertThat(output.toString().substring(outputBeforeCall))
                .contains("MCP admin endpoint refused, two-step sign-in is off userId=1");
        verifyNoInteractions(moderationService, urlRepository);
    }

    @Test
    void adminEndpointRefusesAnInactiveAdminWith403() throws Exception {
        signedIn(User.builder().id(1L).email(ADMIN_EMAIL).active(false).twoStepEnabled(true).build());
        final var token = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);

        mockMvc.perform(mcpRequest("/mcp/admin", BLOCK_DOMAIN_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        verifyNoInteractions(moderationService, urlRepository);
    }

    @Test
    void adminEndpointAnswers503WhenTheDatabaseIsDown(final CapturedOutput output) throws Exception {
        final var token = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenThrow(new DataAccessResourceFailureException("connection refused"));

        mockMvc.perform(mcpRequest("/mcp/admin", BLOCK_DOMAIN_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.outage").value("database"))
                .andExpect(jsonPath("$.detail").value("hrva.cc can't reach its database right now. Try again in a minute."));

        assertThat(output.toString()).contains("MCP admin endpoint refused, the database isn't answering exception=")
                .doesNotContain(ADMIN_EMAIL);
        verifyNoInteractions(moderationService, urlRepository);
    }

    @Test
    void adminEndpointRefusesWith403WhenTheAccountCantBeRead() throws Exception {
        final var token = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenThrow(new InvalidDataAccessApiUsageException("bad state"));

        mockMvc.perform(mcpRequest("/mcp/admin", BLOCK_DOMAIN_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        verifyNoInteractions(moderationService, urlRepository);
    }

    @Test
    void modernAdminRequestWithoutATokenPointsAtItsOwnMetadata() throws Exception {
        mockMvc.perform(modernRequest("/mcp/admin", "tools/list", MODERN_TOOLS_LIST))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + ADMIN_RESOURCE_METADATA + "\""));

        verifyNoInteractions(userRepository, userService, moderationService);
    }

    @Test
    void modernNonAdminIsForbidden(final CapturedOutput output) throws Exception {
        final var user = User.builder().id(7L).email(USER_EMAIL).active(true).twoStepEnabled(true).build();
        signedIn(user);
        final var token = accessTokens.issue(USER_EMAIL, ADMIN_RESOURCE);

        final var outputBeforeCall = output.length();
        mockMvc.perform(modernRequest("/mcp/admin", "tools/list", MODERN_TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("block_domain"))));
        mockMvc.perform(modernToolCall("/mcp/admin", "block_domain", MODERN_BLOCK_DOMAIN_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        assertThat(output.toString().substring(outputBeforeCall))
                .contains("MCP admin endpoint refused a non-admin userId=7")
                .doesNotContain(USER_EMAIL);
        verifyNoInteractions(moderationService, reviewQueueService, urlRepository);
    }

    @Test
    void moderationToolsOnlyOnAdmin() throws Exception {
        signedIn(admin(true));
        final var mcpToken = accessTokens.issue(ADMIN_EMAIL, MCP_RESOURCE);
        final var adminToken = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);

        final var mcpBody = mockMvc.perform(modernRequest("/mcp", "tools/list", MODERN_TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + mcpToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final var adminBody = mockMvc.perform(modernRequest("/mcp/admin", "tools/list", MODERN_TOOLS_LIST)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.<List<String>>read(mcpBody, "$.result.tools[*].name"))
                .containsExactlyInAnyOrder("link_stats", "list_links", "shorten_url")
                .doesNotContainAnyElementsOf(MODERATION_TOOLS);
        assertThat(JsonPath.<List<String>>read(adminBody, "$.result.tools[*].name")).containsExactlyInAnyOrderElementsOf(MODERATION_TOOLS);
    }

    @Test
    void anAdminBlocksADomainOverModernMcpAsTheTokenSubject() throws Exception {
        signedIn(admin(true));
        final var token = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);
        final var callerDuringBlock = new AtomicReference<String>();
        when(urlRepository.findFlaggedOrBlockedDestinationsMentioning("jofogas.cards", "jofogas.cards"))
                .thenReturn(List.of("https://login.jofogas.cards/pay"));
        when(moderationService.blockDomain("jofogas.cards", "lookalike", ADMIN_EMAIL, "mcp", 50)).thenAnswer(invocation -> {
            callerDuringBlock.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return 2;
        });

        mockMvc.perform(modernToolCall("/mcp/admin", "block_domain", MODERN_BLOCK_DOMAIN_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.resultType").value("complete"))
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text").value("Blocked jofogas.cards and its subdomains. Links blocked now: 2."));

        assertThat(callerDuringBlock.get()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void eraCounterCountsTheAdminEndpoint() throws Exception {
        signedIn(admin(true));
        final var token = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);
        final var modernBefore = adminRequests("modern");
        final var legacyBefore = adminRequests("legacy");

        mockMvc.perform(modernRequest("/mcp/admin", SERVER_DISCOVER, MODERN_DISCOVER).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(mcpRequest("/mcp/admin", TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(adminRequests("modern")).isEqualTo(modernBefore + 1);
        assertThat(adminRequests("legacy")).isEqualTo(legacyBefore + 1);
    }

    @Test
    void killSwitchCoversTheAdminEndpoint() throws Exception {
        signedIn(admin(true));
        final var token = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);

        try {
            SettingsServiceFixtures.turnOff(settingsService, SettingKey.MCP_MODERN);

            mockMvc.perform(modernRequest("/mcp/admin", SERVER_DISCOVER, MODERN_DISCOVER).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(LEGACY_DISCOVER_ANSWER));
        } finally {
            SettingsServiceFixtures.turnOn(settingsService, SettingKey.MCP_MODERN);
        }

        mockMvc.perform(modernRequest("/mcp/admin", SERVER_DISCOVER, MODERN_DISCOVER).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.supportedVersions", contains(MODERN_VERSION)));
    }

    @Test
    void adminEndpointWithoutATokenPointsAtItsOwnMetadata() throws Exception {
        mockMvc.perform(mcpRequest("/mcp/admin", TOOLS_LIST)
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + ADMIN_RESOURCE_METADATA + "\""));
        mockMvc.perform(get("/mcp/admin"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("resource_metadata=\"" + ADMIN_RESOURCE_METADATA + "\"")));

        verifyNoInteractions(userRepository, userService, moderationService);
    }

    @Test
    void aTokenForMcpDoesNotOpenMcpAdmin() throws Exception {
        signedIn(admin(true));
        final var mcpToken = accessTokens.issue(ADMIN_EMAIL, MCP_RESOURCE);
        final var adminToken = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);

        mockMvc.perform(mcpRequest("/mcp/admin", TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + mcpToken))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("resource_metadata=\"" + ADMIN_RESOURCE_METADATA + "\"")));
        mockMvc.perform(mcpRequest("/mcp", TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")));

        verifyNoInteractions(moderationService);
    }

    @Test
    void aTokenForMcpDoesNotOpenMcpAdminOverModernMcp() throws Exception {
        signedIn(admin(true));
        final var mcpToken = accessTokens.issue(ADMIN_EMAIL, MCP_RESOURCE);
        final var adminToken = accessTokens.issue(ADMIN_EMAIL, ADMIN_RESOURCE);
        final var requestsBefore = mcpRequests();

        final var adminRefusal = refusedTheSameInBothEras("/mcp/admin", mcpToken);
        final var mcpRefusal = refusedTheSameInBothEras("/mcp", adminToken);

        assertThat(adminRefusal.getStatus()).isEqualTo(401);
        assertThat(adminRefusal.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .contains("error=\"invalid_token\"")
                .contains("resource_metadata=\"" + ADMIN_RESOURCE_METADATA + "\"");
        assertThat(mcpRefusal.getStatus()).isEqualTo(401);
        assertThat(mcpRefusal.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"invalid_token\"");
        assertThat(mcpRequests()).isEqualTo(requestsBefore);
        verifyNoInteractions(moderationService);
    }

    @Test
    void adminMetadataNamesTheAdminResource() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp/admin")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value(ADMIN_RESOURCE))
                .andExpect(jsonPath("$.authorization_servers.length()").value(1))
                .andExpect(jsonPath("$.authorization_servers[0]").value(ISSUER))
                .andExpect(jsonPath("$.resource_name").value("hrva.cc admin"))
                .andExpect(jsonPath("$.bearer_methods_supported", hasItem("header")))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false))
                .andExpect(content().string(not(containsString("evil.example"))));
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value(MCP_RESOURCE))
                .andExpect(jsonPath("$.resource_name").value("hrva.cc"));
    }

    private void signedIn(final User user) {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(userRepository.existsByIdAndAuthoritiesName(user.getId(), "ROLE_ADMIN")).thenReturn(ADMIN_EMAIL.equals(user.getEmail()));
        when(userService.fetchActiveUserByEmail(user.getEmail())).thenReturn(user);
    }

    private static User admin(final boolean twoStepEnabled) {
        return User.builder().id(1L).email(ADMIN_EMAIL).active(true).twoStepEnabled(twoStepEnabled).build();
    }

    private static MockHttpServletRequestBuilder mcpRequest(final String path, final String body) {
        return post(path)
                .header(HttpHeaders.ACCEPT, MCP_ACCEPT)
                .contentType(APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletResponse refusedTheSameInBothEras(final String path, final String token) throws Exception {
        final var legacy = mockMvc.perform(mcpRequest(path, TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn()
                .getResponse();

        for (final var modernRequest : List.of(modernRequest(path, SERVER_DISCOVER, MODERN_DISCOVER), modernRequest(path, "tools/list", MODERN_TOOLS_LIST))) {
            final var modern = mockMvc.perform(modernRequest.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn()
                    .getResponse();
            assertThat(modern.getStatus()).isEqualTo(legacy.getStatus());
            assertThat(modern.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(legacy.getHeader(HttpHeaders.WWW_AUTHENTICATE));
            assertThat(modern.getContentAsString()).isEqualTo(legacy.getContentAsString());
        }

        return legacy;
    }

    private double mcpRequests() {
        return meterRegistry.find(MCP_REQUESTS).counters().stream().mapToDouble(Counter::count).sum();
    }

    private double adminRequests(final String era) {
        return meterRegistry.get(MCP_REQUESTS).tags("endpoint", "/mcp/admin", "era", era, "header", "ok").counter().count();
    }

    private static MockHttpServletRequestBuilder modernRequest(final String path, final String method, final String body) {
        return mcpRequest(path, body)
                .header(PROTOCOL_VERSION_HEADER, MODERN_VERSION)
                .header(METHOD_HEADER, method);
    }

    private static MockHttpServletRequestBuilder modernToolCall(final String path, final String tool, final String body) {
        return modernRequest(path, "tools/call", body).header(NAME_HEADER, tool);
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
    @ComponentScan(basePackageClasses = LinkTools.class)
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
            AnyHostIsPrimary.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            McpMessageBodyLogFilter.class,
            JwtFilter.class,
            ClientIpResolver.class,
            ClockConfiguration.class,
            AdminMcpServerConfiguration.class,
            ShortLinks.class,
            ModernMcpServers.class,
            McpEraFilterConfiguration.class,
            DefaultSettingsTestConfiguration.class
    })
    static class TestApplication {
    }

}
