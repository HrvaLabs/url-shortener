package cc.hrva.urlshortener.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
public class McpModernProbeFilter extends OncePerRequestFilter {

    public static final String MCP_METHOD_HEADER = "Mcp-Method";
    public static final String SERVER_DISCOVER = "server/discover";

    private static final String LEGACY_SERVER_ANSWER = """
            {"jsonrpc":"2.0","id":null,"error":{"code":-32601,"message":"Method not found: server/discover. This server implements MCP 2025-11-25; send initialize."}}""";

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !SERVER_DISCOVER.equals(request.getHeader(MCP_METHOD_HEADER));
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {
        log.debug("Answered a modern MCP discovery probe so the client falls back to initialize");
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(LEGACY_SERVER_ANSWER);
    }

}
