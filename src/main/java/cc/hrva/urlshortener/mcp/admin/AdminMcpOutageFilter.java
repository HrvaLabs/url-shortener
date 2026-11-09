package cc.hrva.urlshortener.mcp.admin;

import cc.hrva.urlshortener.util.DatabaseOutages;
import cc.hrva.urlshortener.util.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
public class AdminMcpOutageFilter extends OncePerRequestFilter {

    private static final Duration RETRY = Duration.ofMinutes(1);

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (final RuntimeException exception) {
            final var redis = DatabaseOutages.isRedis(exception);
            if (!redis && !DatabaseOutages.isOutage(exception)) {
                throw exception;
            }
            log.warn("MCP admin endpoint refused, the {} isn't answering exception={}", redis ? "cache" : "database", exception.getClass().getName());
            ProblemResponses.retryAfter(response, RETRY);
            ProblemResponses.write(request, response, redis ? DatabaseOutages.cacheProblem() : DatabaseOutages.problem());
        }
    }

}
