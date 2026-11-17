package cc.hrva.urlshortener.mcp;

import cc.hrva.urlshortener.mcp.admin.AdminMcpServerConfiguration;
import cc.hrva.urlshortener.mcp.modern.ModernMcpTransports;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public class McpEraFilter extends OncePerRequestFilter {

    static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    private static final String MCP_ENDPOINT = "/mcp";
    private static final List<String> ENDPOINTS = List.of(MCP_ENDPOINT, AdminMcpServerConfiguration.ENDPOINT);
    private static final String MCP_METHOD_HEADER = "Mcp-Method";
    private static final String SERVER_DISCOVER = "server/discover";
    private static final String LEGACY_SERVER_ANSWER = """
            {"jsonrpc":"2.0","id":null,"error":{"code":-32601,"message":"Method not found: server/discover. This server implements MCP 2025-11-25; send initialize."}}""";
    private static final String REQUESTS = "hrva.mcp.requests";
    private static final String ENDPOINT = "endpoint";
    private static final String ERA = "era";
    private static final String HEADER = "header";
    private static final String HEADER_OK = "ok";
    private static final String HEADER_CORRECTED = "corrected";
    private static final Pattern VERSION = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final String UNREADABLE_VERSION = "an unreadable version";

    private final ModernMcpTransports transports;
    private final SettingsService settingsService;
    private final JsonMapper jsonMapper;
    private final Map<String, Counter> legacyRequests;
    private final Map<String, Counter> modernRequests;
    private final Map<String, Counter> correctedRequests;

    public McpEraFilter(final ModernMcpTransports transports, final SettingsService settingsService, final JsonMapper jsonMapper,
                        final MeterRegistry meterRegistry) {
        this.transports = transports;
        this.settingsService = settingsService;
        this.jsonMapper = jsonMapper;
        this.legacyRequests = requests(meterRegistry, McpEra.LEGACY, HEADER_OK);
        this.modernRequests = requests(meterRegistry, McpEra.MODERN, HEADER_OK);
        this.correctedRequests = requests(meterRegistry, McpEra.MODERN, HEADER_CORRECTED);
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {
        if (!signedInWithAnMcpToken()) {
            filterChain.doFilter(request, response);
            return;
        }

        final var endpoint = endpointOf(request);
        if (!settingsService.isOn(SettingKey.MCP_MODERN)) {
            legacyRequests.get(endpoint).increment();
            if (SERVER_DISCOVER.equals(request.getHeader(MCP_METHOD_HEADER))) {
                answerAsALegacyServer(response);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        final var body = request.getInputStream().readAllBytes();
        final var versionHeader = request.getHeader(PROTOCOL_VERSION_HEADER);
        final var decision = McpEraDecision.decide(request.getMethod(), versionHeader, body, jsonMapper);
        if (decision.era() == McpEra.LEGACY) {
            legacyRequests.get(endpoint).increment();
            filterChain.doFilter(new ReplayableRequest(request, body), response);
            return;
        }

        final var correctedVersion = oneVersionHeader(request) ? decision.correctedProtocolVersion() : null;
        if (correctedVersion == null) {
            modernRequests.get(endpoint).increment();
        } else {
            correctedRequests.get(endpoint).increment();
            log.info("Corrected an MCP-Protocol-Version header on {}: {} -> {}", endpoint, versionHeader.trim(), loggable(correctedVersion));
        }
        transports.forEndpoint(endpoint).service(new ReplayableRequest(request, body, correctedVersion), response);
    }

    private static boolean signedInWithAnMcpToken() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication && authentication.isAuthenticated();
    }

    private static String endpointOf(final HttpServletRequest request) {
        final var path = StringUtils.removeStart(request.getRequestURI(), request.getContextPath());

        return AdminMcpServerConfiguration.ENDPOINT.equals(path) ? AdminMcpServerConfiguration.ENDPOINT : MCP_ENDPOINT;
    }

    private static boolean oneVersionHeader(final HttpServletRequest request) {
        return Collections.list(request.getHeaders(PROTOCOL_VERSION_HEADER)).size() == 1;
    }

    private static String loggable(final String version) {
        return VERSION.matcher(version).matches() ? version : UNREADABLE_VERSION;
    }

    private static void answerAsALegacyServer(final HttpServletResponse response) throws IOException {
        log.debug("Answered a modern MCP discovery probe so the client falls back to initialize");
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(LEGACY_SERVER_ANSWER);
    }

    private static Map<String, Counter> requests(final MeterRegistry meterRegistry, final McpEra era, final String header) {
        return ENDPOINTS.stream().collect(Collectors.toUnmodifiableMap(Function.identity(), endpoint -> Counter.builder(REQUESTS)
                .tag(ENDPOINT, endpoint)
                .tag(ERA, era.name().toLowerCase(Locale.ROOT))
                .tag(HEADER, header)
                .description("Signed-in MCP POSTs by endpoint, by MCP version (modern is 2026-07-28, legacy is 2025-11-25 and older), and by whether "
                        + "a legacy MCP-Protocol-Version header was corrected to the version in the body")
                .register(meterRegistry)));
    }

}
