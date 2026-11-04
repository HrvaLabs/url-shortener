package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.controller.LinkPageHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

@Slf4j
public class UnknownPageFilter extends OncePerRequestFilter {

    private static final Set<String> SERVED_METHODS = Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name());
    private static final Set<String> KEPT_FIRST_SEGMENTS = Set.of(
            "api", "actuator", "oauth2", "login", "logout", ".well-known", "mcp", "swagger-ui", "v3", "error");
    private static final Set<String> KEPT_SINGLE_SEGMENTS = Set.of("favicon.ico", "robots.txt", ".well-known", "swagger-ui.html");
    private static final Pattern ALIAS = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final String HANDLE_PREFIX = "@";
    private static final String SLASH = "/";
    private static final String CONTENT_TYPE = "text/html;charset=UTF-8";
    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="light dark">
            <meta name="robots" content="noindex, nofollow">
            <title>Link not found &middot; hrva.cc</title>
            </head>
            <body>
            <h1>This link doesn't exist</h1>
            <p>hrva.cc has no link with this name.</p>
            <p><a href="%s">Go to hrva.cc</a></p>
            </body>
            </html>
            """;

    private final byte[] page;

    public UnknownPageFilter(final AppProperties appProperties) {
        final var frontendUrl = appProperties.getFrontendUrl();
        final var startUrl = frontendUrl.endsWith(SLASH) ? frontendUrl : frontendUrl + SLASH;
        this.page = PAGE.formatted(HtmlUtils.htmlEscape(startUrl)).getBytes(StandardCharsets.UTF_8);
    }

    public static boolean isUnknownPage(final String method, final String path) {
        if (!SERVED_METHODS.contains(method) || !StringUtils.startsWith(path, SLASH)) {
            return false;
        }
        final var rest = path.substring(SLASH.length());
        final var firstSlash = rest.indexOf(SLASH);
        if (firstSlash < 0) {
            return cannotBeAnAlias(rest);
        }
        if (firstSlash == rest.length() - 1) {
            return cannotBeAnAlias(rest.substring(0, firstSlash));
        }
        return !KEPT_FIRST_SEGMENTS.contains(rest.substring(0, firstSlash).toLowerCase(Locale.ROOT));
    }

    private static boolean cannotBeAnAlias(final String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        final String decoded;
        try {
            decoded = UriUtils.decode(segment, StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException exception) {
            return true;
        }
        if (decoded.startsWith(HANDLE_PREFIX) || KEPT_SINGLE_SEGMENTS.contains(decoded.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return !ALIAS.matcher(decoded).matches();
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !isUnknownPage(request.getMethod(), pathOf(request));
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain) throws IOException {
        log.debug("Unknown page answered with the not-found page method={}", request.getMethod());
        LinkPageHeaders.apply(response);
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(page.length);
        if (!HttpMethod.HEAD.matches(request.getMethod())) {
            response.getOutputStream().write(page);
        }
    }

    private static String pathOf(final HttpServletRequest request) {
        return StringUtils.removeStart(request.getRequestURI(), request.getContextPath());
    }

}
