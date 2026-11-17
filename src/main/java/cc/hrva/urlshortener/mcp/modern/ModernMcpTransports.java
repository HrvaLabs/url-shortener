package cc.hrva.urlshortener.mcp.modern;

import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;

public final class ModernMcpTransports {

    private final Map<String, HttpServletMcpTransport> transports;

    public ModernMcpTransports(final Map<String, HttpServletMcpTransport> transports) {
        this.transports = Map.copyOf(transports);
    }

    public HttpServletMcpTransport forEndpoint(final String endpoint) {
        return Optional.ofNullable(transports.get(endpoint))
                .orElseThrow(() -> new IllegalArgumentException("No MCP 2026-07-28 server on " + endpoint));
    }

    @PreDestroy
    void destroy() {
        transports.values().forEach(HttpServletMcpTransport::closeGracefully);
    }

}
