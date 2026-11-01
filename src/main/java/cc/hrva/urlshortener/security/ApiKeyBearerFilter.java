package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.util.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiKeyBearerFilter extends OncePerRequestFilter {

    public static final String TWO_KEYS = "Send one API key: in X-Api-Key or as Authorization: Bearer, not two different ones.";
    private static final String BEARER = "Bearer ";
    private static final String KEY_PREFIX = "hrva_";
    private static final String LINK_API = "/api/v1/urls";

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        final var path = StringUtils.removeStart(request.getRequestURI(), request.getContextPath());
        final var linkApi = LINK_API.equals(path) || StringUtils.startsWith(path, LINK_API + "/");
        return !linkApi || keyIn(request) == null;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {
        final var key = keyIn(request);
        final var sentAsHeader = request.getHeader(ApiKeyRateLimitFilter.API_KEY_HEADER);
        if (StringUtils.isNotBlank(sentAsHeader) && !sentAsHeader.equals(key)) {
            ProblemResponses.write(request, response, HttpStatus.BAD_REQUEST, TWO_KEYS);
            return;
        }
        filterChain.doFilter(new KeyAsApiKeyHeader(request, key), response);
    }

    private static String keyIn(final HttpServletRequest request) {
        final var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER + KEY_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER.length()).strip();
    }

    private static final class KeyAsApiKeyHeader extends HttpServletRequestWrapper {

        private final String key;

        private KeyAsApiKeyHeader(final HttpServletRequest request, final String key) {
            super(request);
            this.key = key;
        }

        @Override
        public String getHeader(final String name) {
            if (ApiKeyRateLimitFilter.API_KEY_HEADER.equalsIgnoreCase(name)) {
                return key;
            }
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(final String name) {
            if (ApiKeyRateLimitFilter.API_KEY_HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(key));
            }
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            final var others = Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) && !ApiKeyRateLimitFilter.API_KEY_HEADER.equalsIgnoreCase(name));
            return Collections.enumeration(Stream.concat(others, Stream.of(ApiKeyRateLimitFilter.API_KEY_HEADER)).toList());
        }

    }

}
