package cc.hrva.urlshortener.mcp.modern;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.mcp.admin.AdminMcpServerConfiguration;
import cc.hrva.urlshortener.mcp.admin.AdminMcpTools;
import cc.hrva.urlshortener.mcp.tools.LinkTools;
import cc.hrva.urlshortener.mcp.tools.ModerationTools;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ModernMcpServersTest.TestApplication.class,
        properties = {
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost",
                "app.mcp.signing-key=unused-in-this-test",
                "spring.ai.mcp.server.instructions=Test instructions for both servers."
        })
@ExtendWith(OutputCaptureExtension.class)
class ModernMcpServersTest {

    private static final String MCP = "/mcp";
    private static final String ADMIN = "/mcp/admin";
    private static final String MODERN_VERSION = "2026-07-28";
    private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    private static final String METHOD_HEADER = "Mcp-Method";
    private static final String NAME_HEADER = "Mcp-Name";
    private static final String EMAIL = "user@localhost";
    private static final String SERVER_INFO = "$.result._meta['io.modelcontextprotocol/serverInfo']";
    private static final List<String> LINK_TOOLS = List.of("list_links", "shorten_url", "link_stats");
    private static final List<String> MODERATION_TOOLS = List.of("block_domain", "block_link", "block_user", "check_url", "delete_link",
            "moderation_summary", "possible_scammers", "review_queue");
    private static final String DISCOVER = """
            {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String LIST_LINKS_CALL = """
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_links","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String UNKNOWN_METHOD = """
            {"jsonrpc":"2.0","id":4,"method":"links/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String FUTURE_VERSION_DISCOVER = """
            {"jsonrpc":"2.0","id":5,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2099-01-01","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String LEGACY_TOOLS_LIST = """
            {"jsonrpc":"2.0","id":6,"method":"tools/list","params":{}}""";

    @Autowired
    private ModernMcpTransports transports;

    @Autowired
    private McpServerProperties serverProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("mcpServerJsonMapper")
    private JsonMapper mcpServerJsonMapper;

    @Autowired
    private LinkTools linkTools;

    @Autowired
    private AdminMcpTools adminMcpTools;

    @Autowired
    @Qualifier("webMvcStatelessServerRouterFunction")
    private RouterFunction<ServerResponse> legacyMcpRouterFunction;

    @Autowired
    @Qualifier("adminMcpRouterFunction")
    private RouterFunction<ServerResponse> legacyAdminMcpRouterFunction;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AnalyticsService analyticsService;

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
    private UserRepository userRepository;

    @MockitoBean
    private UrlRepository urlRepository;

    @MockitoBean
    private LinkReviewRepository linkReviewRepository;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {MCP, ADMIN})
    void discoverAnswersTheModernVersion(final String endpoint) throws Exception {
        final var response = send(endpoint, DISCOVER, modernHeaders("server/discover"));

        assertThat(response.getStatus()).isEqualTo(200);
        final var body = response.getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.result.supportedVersions")).containsExactly(MODERN_VERSION);
        assertThat(JsonPath.<String>read(body, SERVER_INFO + ".name")).isEqualTo("hrva.cc");
        assertThat(JsonPath.<String>read(body, SERVER_INFO + ".version")).isEqualTo(serverProperties.getVersion());
        assertThat(JsonPath.<String>read(body, "$.result.instructions")).isEqualTo("Test instructions for both servers.");
        assertThat(JsonPath.<Number>read(body, "$.result.ttlMs").longValue()).isEqualTo(300_000L);
        assertThat(JsonPath.<String>read(body, "$.result.cacheScope")).isEqualTo("private");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.result.capabilities")).containsKey("tools");
    }

    @Test
    void toolsListMatchesTheLegacyTools() throws Exception {
        final var mcp = send(MCP, TOOLS_LIST, modernHeaders("tools/list"));
        final var admin = send(ADMIN, TOOLS_LIST, modernHeaders("tools/list"));

        assertThat(mcp.getStatus()).isEqualTo(200);
        assertThat(admin.getStatus()).isEqualTo(200);
        final var mcpBody = mcp.getContentAsString();
        final var adminBody = admin.getContentAsString();
        final var legacyMcpBody = legacyToolsList(legacyMcpRouterFunction, MCP);
        final var legacyAdminBody = legacyToolsList(legacyAdminMcpRouterFunction, ADMIN);
        assertThat(JsonPath.<List<Object>>read(mcpBody, "$.result.tools")).isEqualTo(JsonPath.read(legacyMcpBody, "$.result.tools"));
        assertThat(JsonPath.<List<Object>>read(adminBody, "$.result.tools")).isEqualTo(JsonPath.read(legacyAdminBody, "$.result.tools"));
        assertThat(JsonPath.<List<String>>read(mcpBody, "$.result.tools[*].name"))
                .containsExactlyInAnyOrderElementsOf(LINK_TOOLS)
                .containsExactlyElementsOf(JsonPath.read(legacyMcpBody, "$.result.tools[*].name"))
                .doesNotContainAnyElementsOf(MODERATION_TOOLS);
        assertThat(JsonPath.<List<String>>read(adminBody, "$.result.tools[*].name"))
                .containsExactlyInAnyOrderElementsOf(MODERATION_TOOLS)
                .containsExactlyElementsOf(JsonPath.read(legacyAdminBody, "$.result.tools[*].name"));
        assertThat(List.of(mcpBody, adminBody)).allSatisfy(body -> {
            assertThat(JsonPath.<Number>read(body, "$.result.ttlMs").longValue()).isEqualTo(300_000L);
            assertThat(JsonPath.<String>read(body, "$.result.cacheScope")).isEqualTo("private");
        });
    }

    @Test
    void toolsCallRunsTheToolAsTheSignedInUser() throws Exception {
        final var user = User.builder().id(7L).email(EMAIL).active(true).build();
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(user);
        when(urlService.getAllMyUrls(isNull(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new UrlResponse(1L, "https://example.com/first", "abc12", LocalDateTime.now(), null, 3L, null, true,
                        "ACTIVE", Set.of(), false, EMAIL))));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(EMAIL)
                .build()));

        final var response = send(MCP, LIST_LINKS_CALL, Map.of(PROTOCOL_VERSION_HEADER, MODERN_VERSION, METHOD_HEADER, "tools/call",
                NAME_HEADER, "list_links"));

        assertThat(response.getStatus()).isEqualTo(200);
        final var body = response.getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.result.resultType")).isEqualTo("complete");
        assertThat(JsonPath.<Boolean>read(body, "$.result.isError")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.result.content[0].text"))
                .isEqualTo("https://hrva.cc/abc12 -> https://example.com/first | 3 visits | ACTIVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {MCP, ADMIN})
    void unknownMethodIs404(final String endpoint) throws Exception {
        final var response = send(endpoint, UNKNOWN_METHOD, modernHeaders("links/list"));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(JsonPath.<Integer>read(response.getContentAsString(), "$.error.code")).isEqualTo(-32601);
    }

    @ParameterizedTest
    @ValueSource(strings = {MCP, ADMIN})
    void unsupportedVersionIs400WithTheSupportedList(final String endpoint) throws Exception {
        final var response = send(endpoint, FUTURE_VERSION_DISCOVER, Map.of(PROTOCOL_VERSION_HEADER, "2099-01-01", METHOD_HEADER, "server/discover"));

        assertThat(response.getStatus()).isEqualTo(400);
        final var body = response.getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.error.code")).isEqualTo(-32022);
        assertThat(JsonPath.<List<String>>read(body, "$.error.data.supported")).containsExactly(MODERN_VERSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {MCP, ADMIN})
    void originHeaderIsAccepted(final String endpoint) throws Exception {
        final var response = send(endpoint, DISCOVER, Map.of(PROTOCOL_VERSION_HEADER, MODERN_VERSION, METHOD_HEADER, "server/discover",
                HttpHeaders.ORIGIN, "https://claude.ai"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.result.supportedVersions")).containsExactly(MODERN_VERSION);
    }

    @Test
    void onlyMcpAndMcpAdminHaveATransport() {
        assertThat(transports.forEndpoint(MCP)).isNotSameAs(transports.forEndpoint(ADMIN));
        assertThatIllegalArgumentException().isThrownBy(() -> transports.forEndpoint("/mcp/other"));
    }

    @Test
    void moderationToolsAreNeverABean() {
        assertThat(applicationContext.getBeanNamesForType(ModerationTools.class)).isEmpty();
    }

    @Test
    void buildingTheServersLogsNoRequestStateKeyWarning(final CapturedOutput output) {
        final var outputBeforeBuild = output.length();

        new ModernMcpServers(serverProperties).modernMcpTransports(mcpServerJsonMapper, linkTools, adminMcpTools);

        assertThat(output.toString().substring(outputBeforeBuild))
                .doesNotContain("HmacRequestStateCodec")
                .doesNotContain("no key configured");
    }

    @Test
    void closedTransportsRefuseNewRequests() throws Exception {
        final var closing = new ModernMcpServers(serverProperties).modernMcpTransports(mcpServerJsonMapper, linkTools, adminMcpTools);

        closing.destroy();

        assertThat(closing).isNotSameAs(transports);

        for (final var endpoint : List.of(MCP, ADMIN)) {
            final var response = new MockHttpServletResponse();
            closing.forEndpoint(endpoint).service(request(endpoint, DISCOVER, modernHeaders("server/discover")), response);
            assertThat(response.getStatus()).isEqualTo(503);
        }
    }

    private MockHttpServletResponse send(final String endpoint, final String body, final Map<String, String> headers) throws Exception {
        final var response = new MockHttpServletResponse();
        transports.forEndpoint(endpoint).service(request(endpoint, body, headers), response);

        return response;
    }

    private static MockHttpServletRequest request(final String endpoint, final String body, final Map<String, String> headers) {
        final var request = new MockHttpServletRequest(HttpMethod.POST.name(), endpoint);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        headers.forEach(request::addHeader);

        return request;
    }

    private static Map<String, String> modernHeaders(final String method) {
        return Map.of(PROTOCOL_VERSION_HEADER, MODERN_VERSION, METHOD_HEADER, method);
    }

    private static String legacyToolsList(final RouterFunction<ServerResponse> routerFunction, final String endpoint) throws Exception {
        return MockMvcBuilders.routerFunctions(routerFunction).build()
                .perform(post(endpoint)
                        .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LEGACY_TOOLS_LIST))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({
            McpServerJsonMapperAutoConfiguration.class,
            McpServerStatelessAutoConfiguration.class,
            McpServerStatelessWebMvcAutoConfiguration.class,
            McpServerAnnotationScannerAutoConfiguration.class,
            StatelessServerSpecificationFactoryAutoConfiguration.class,
            ValidationAutoConfiguration.class
    })
    @ComponentScan(basePackageClasses = LinkTools.class)
    @Import({
            AppConfiguration.class,
            ClockConfiguration.class,
            ShortLinks.class,
            AdminMcpServerConfiguration.class,
            ModernMcpServers.class
    })
    static class TestApplication {
    }

}
