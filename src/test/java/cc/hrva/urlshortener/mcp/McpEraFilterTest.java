package cc.hrva.urlshortener.mcp;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.modern.ModernMcpTransports;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(OutputCaptureExtension.class)
class McpEraFilterTest {

    private static final String MCP = "/mcp";
    private static final String ADMIN = "/mcp/admin";
    private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    private static final String METHOD_HEADER = "Mcp-Method";
    private static final String MODERN_VERSION = "2026-07-28";
    private static final String LEGACY_VERSION = "2025-11-25";
    private static final String REQUESTS = "hrva.mcp.requests";
    private static final String LEGACY_DISCOVER_ANSWER = """
            {"jsonrpc":"2.0","id":null,"error":{"code":-32601,"message":"Method not found: server/discover. This server implements MCP 2025-11-25; send initialize."}}""";
    private static final String MODERN_DISCOVER = """
            {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String MODERN_TOOLS_LIST = """
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String LEGACY_TOOLS_LIST = """
            {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}""";
    private static final String FORGED_VERSION_TOOLS_LIST = """
            {"jsonrpc":"2.0","id":4,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28\\nFORGED log line","io.modelcontextprotocol/clientCapabilities":{}}}}""";

    private final HttpServletMcpTransport mcpTransport = mock(HttpServletMcpTransport.class);
    private final HttpServletMcpTransport adminTransport = mock(HttpServletMcpTransport.class);
    private final SettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final McpEraFilter filter = new McpEraFilter(new ModernMcpTransports(Map.of(MCP, mcpTransport, ADMIN, adminTransport)), settingsService,
            JsonMapper.builder().build(), meterRegistry);

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(), List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void everySeriesIsThereFromTheStart() {
        assertThat(meterRegistry.find(REQUESTS).counters())
                .extracting(counter -> counter.getId().getTag("endpoint") + " " + counter.getId().getTag("era") + " " + counter.getId().getTag("header"))
                .containsExactlyInAnyOrder("/mcp legacy ok", "/mcp modern ok", "/mcp modern corrected",
                        "/mcp/admin legacy ok", "/mcp/admin modern ok", "/mcp/admin modern corrected");
        assertThat(meterRegistry.find(REQUESTS).counters()).allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void registeredForBothEndpointsAfterSpringSecurityAndTheBodyLimit() {
        final var registration = new McpEraFilterConfiguration().mcpEraFilterRegistration(new ModernMcpTransports(Map.of()), settingsService,
                JsonMapper.builder().build(), meterRegistry);
        final var bodyLimit = new AuthorizationServerConfiguration(new AppProperties(), new MockEnvironment()).mcpRequestBodyLimitFilterRegistration();

        assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrder(MCP, ADMIN);
        assertThat(registration.getOrder()).isEqualTo(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 100).isGreaterThan(bodyLimit.getOrder());
    }

    @Test
    void getIsLeftAlone() throws Exception {
        final var request = new MockHttpServletRequest("GET", MCP);
        final var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(totalRequests()).isZero();
        verifyNoInteractions(mcpTransport, adminTransport);
    }

    @Test
    void aLegacyRequestContinuesWithItsWholeBody() throws Exception {
        final var chain = new MockFilterChain();

        filter.doFilter(post(MCP, LEGACY_TOOLS_LIST, Map.of()), new MockHttpServletResponse(), chain);

        final var passedOn = (HttpServletRequest) chain.getRequest();
        assertThat(passedOn).isInstanceOf(ReplayableRequest.class);
        assertThat(new String(passedOn.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(LEGACY_TOOLS_LIST);
        assertThat(passedOn.getReader().lines().collect(Collectors.joining("\n"))).isEqualTo(LEGACY_TOOLS_LIST);
        assertThat(requests(MCP, "legacy", "ok")).isEqualTo(1);
        assertThat(totalRequests()).isEqualTo(1);
        verifyNoInteractions(mcpTransport, adminTransport);
    }

    @ParameterizedTest
    @ValueSource(strings = {MCP, ADMIN})
    void aModernRequestIsServedByItsEndpointsServer(final String endpoint) throws Exception {
        final var response = new MockHttpServletResponse();
        final var chain = new MockFilterChain();

        filter.doFilter(post(endpoint, MODERN_TOOLS_LIST, Map.of(PROTOCOL_VERSION_HEADER, MODERN_VERSION, METHOD_HEADER, "tools/list")), response, chain);

        final var served = servedBy(MCP.equals(endpoint) ? mcpTransport : adminTransport, response);
        verifyNoInteractions(MCP.equals(endpoint) ? adminTransport : mcpTransport);
        assertThat(chain.getRequest()).isNull();
        assertThat(new String(served.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(MODERN_TOOLS_LIST);
        assertThat(served.getHeader(PROTOCOL_VERSION_HEADER)).isEqualTo(MODERN_VERSION);
        assertThat(requests(endpoint, "modern", "ok")).isEqualTo(1);
        assertThat(totalRequests()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("notSignedInWithAnMcpToken")
    void aModernRequestWithoutAnMcpSignInContinuesUnread(final Authentication authentication) throws Exception {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(authentication);

        for (final var endpoint : List.of(MCP, ADMIN)) {
            final var request = post(endpoint, MODERN_TOOLS_LIST, Map.of(PROTOCOL_VERSION_HEADER, MODERN_VERSION, METHOD_HEADER, "tools/list"));
            final var chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).isSameAs(request);
            assertThat(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(MODERN_TOOLS_LIST);
        }
        verifyNoInteractions(mcpTransport, adminTransport);
        assertThat(totalRequests()).isZero();
    }

    @Test
    void aLegacyVersionHeaderOnAModernBodyIsCorrected(final CapturedOutput output) throws Exception {
        final var response = new MockHttpServletResponse();

        filter.doFilter(post(MCP, MODERN_TOOLS_LIST, Map.of(PROTOCOL_VERSION_HEADER, LEGACY_VERSION, METHOD_HEADER, "tools/list")), response,
                new MockFilterChain());

        final var served = servedBy(mcpTransport, response);
        assertThat(served.getHeader("mcp-protocol-version")).isEqualTo(MODERN_VERSION);
        assertThat(Collections.list(served.getHeaders("Mcp-Protocol-Version"))).containsExactly(MODERN_VERSION);
        assertThat(served.getHeader(METHOD_HEADER)).isEqualTo("tools/list");
        assertThat(output).contains("Corrected an MCP-Protocol-Version header on /mcp: 2025-11-25 -> 2026-07-28");
        assertThat(requests(MCP, "modern", "corrected")).isEqualTo(1);
        assertThat(totalRequests()).isEqualTo(1);
    }

    @Test
    void aRepeatedVersionHeaderIsLeftForTheServerToRefuse(final CapturedOutput output) throws Exception {
        final var request = post(MCP, MODERN_TOOLS_LIST, Map.of(METHOD_HEADER, "tools/list"));
        request.addHeader(PROTOCOL_VERSION_HEADER, LEGACY_VERSION);
        request.addHeader(PROTOCOL_VERSION_HEADER, LEGACY_VERSION);
        final var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        final var served = servedBy(mcpTransport, response);
        assertThat(Collections.list(served.getHeaders(PROTOCOL_VERSION_HEADER))).containsExactly(LEGACY_VERSION, LEGACY_VERSION);
        assertThat(output).doesNotContain("Corrected an MCP-Protocol-Version header");
        assertThat(requests(MCP, "modern", "ok")).isEqualTo(1);
    }

    @Test
    void aBodyVersionThatIsNotADateIsNotLogged(final CapturedOutput output) throws Exception {
        final var response = new MockHttpServletResponse();

        filter.doFilter(post(MCP, FORGED_VERSION_TOOLS_LIST, Map.of(PROTOCOL_VERSION_HEADER, LEGACY_VERSION, METHOD_HEADER, "tools/list")), response,
                new MockFilterChain());

        servedBy(mcpTransport, response);
        assertThat(output)
                .contains("Corrected an MCP-Protocol-Version header on /mcp: 2025-11-25 -> an unreadable version")
                .doesNotContain("FORGED");
    }

    @Test
    void switchedOffDiscoveryGetsTheLegacyAnswerUntilItIsOnAgain() throws Exception {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.MCP_MODERN);
        final var response = new MockHttpServletResponse();
        final var chain = new MockFilterChain();

        filter.doFilter(post(ADMIN, MODERN_DISCOVER, Map.of(PROTOCOL_VERSION_HEADER, MODERN_VERSION, METHOD_HEADER, "server/discover")), response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(response.getContentAsString()).isEqualTo(LEGACY_DISCOVER_ANSWER);
        assertThat(chain.getRequest()).isNull();
        verifyNoInteractions(mcpTransport, adminTransport);
        assertThat(requests(ADMIN, "legacy", "ok")).isEqualTo(1);

        SettingsServiceFixtures.turnOn(settingsService, SettingKey.MCP_MODERN);
        final var servedResponse = new MockHttpServletResponse();
        filter.doFilter(post(ADMIN, MODERN_DISCOVER, Map.of(PROTOCOL_VERSION_HEADER, MODERN_VERSION, METHOD_HEADER, "server/discover")), servedResponse,
                new MockFilterChain());

        servedBy(adminTransport, servedResponse);
        assertThat(requests(ADMIN, "modern", "ok")).isEqualTo(1);
    }

    @Test
    void switchedOffEveryOtherRequestGoesOnUnread() throws Exception {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.MCP_MODERN);
        final var request = post(MCP, MODERN_TOOLS_LIST, Map.of(PROTOCOL_VERSION_HEADER, MODERN_VERSION, METHOD_HEADER, "tools/list"));
        final var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(MODERN_TOOLS_LIST);
        verifyNoInteractions(mcpTransport, adminTransport);
        assertThat(requests(MCP, "legacy", "ok")).isEqualTo(1);
        assertThat(totalRequests()).isEqualTo(1);
    }

    @Test
    void aBodyOverTheLimitIs413AfterTheFilterStartsReadingIt() throws Exception {
        final var request = new MockHttpServletRequest("POST", MCP) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(("{\"pad\":\"" + "x".repeat(McpRequestBodyLimitFilter.MAX_BODY_BYTES) + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader(PROTOCOL_VERSION_HEADER, MODERN_VERSION);
        final var response = new MockHttpServletResponse();

        new MockFilterChain(new HttpServlet() {
        }, new McpRequestBodyLimitFilter(), filter).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("The request body is over 64 KB.");
        verifyNoInteractions(mcpTransport, adminTransport);
        assertThat(totalRequests()).isZero();
    }

    private HttpServletRequest servedBy(final HttpServletMcpTransport transport, final MockHttpServletResponse response) throws Exception {
        final var served = ArgumentCaptor.forClass(ServletRequest.class);
        verify(transport).service(served.capture(), same(response));
        assertThat(served.getValue()).isInstanceOf(ReplayableRequest.class);

        return (HttpServletRequest) served.getValue();
    }

    private double requests(final String endpoint, final String era, final String header) {
        return meterRegistry.get(REQUESTS).tags("endpoint", endpoint, "era", era, "header", header).counter().count();
    }

    private double totalRequests() {
        return meterRegistry.find(REQUESTS).counters().stream().mapToDouble(Counter::count).sum();
    }

    private static Stream<Named<Authentication>> notSignedInWithAnMcpToken() {
        return Stream.of(
                Named.of("no authentication", null),
                Named.of("an anonymous token", new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))),
                Named.of("an unauthenticated MCP token", new JwtAuthenticationToken(jwt())));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user@localhost")
                .build();
    }

    private static MockHttpServletRequest post(final String endpoint, final String body, final Map<String, String> headers) {
        final var request = new MockHttpServletRequest("POST", endpoint);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        headers.forEach(request::addHeader);

        return request;
    }

}
