package cc.hrva.urlshortener.mcp.authorization;

import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@RequiredArgsConstructor
public class DcrRateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMIT_NAME = "dcr";
    private static final int REGISTRATION_LIMIT = 20;
    private static final Duration REGISTRATION_WINDOW = Duration.ofHours(1);
    private static final String RATE_LIMITED = """
            {"error":"rate_limited","error_description":"Too many app registrations from this network. Try again later."}""";
    private static final String TEMPORARILY_UNAVAILABLE = """
            {"error":"temporarily_unavailable"}""";
    private static final String NO_STORE = "no-store";

    private final RequestMatcher clientRegistrationEndpoint;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !clientRegistrationEndpoint.matches(request);
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {
        final var key = RateLimiter.clientKey(RATE_LIMIT_NAME, clientIpResolver.getClientIp(request));
        final boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(key, REGISTRATION_LIMIT, REGISTRATION_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Client registration refused because the rate limiter is unavailable");
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, TEMPORARILY_UNAVAILABLE);
            return;
        }

        if (!allowed) {
            log.info("Client registration refused by the rate limit");
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(REGISTRATION_WINDOW.toSeconds()));
            writeError(response, HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static void writeError(final HttpServletResponse response, final HttpStatus status, final String body) throws IOException {
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

}
