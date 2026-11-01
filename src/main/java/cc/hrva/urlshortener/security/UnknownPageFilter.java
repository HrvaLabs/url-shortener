package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.controller.LinkUnavailableView;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.thymeleaf.TemplateEngine;

@Slf4j
@RequiredArgsConstructor
public class UnknownPageFilter extends OncePerRequestFilter {

    private static final Set<String> SERVED_METHODS = Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name());
    private static final Set<String> KEPT_FIRST_SEGMENTS = Set.of(
            "api", "actuator", "oauth2", "login", "logout", ".well-known", "mcp", "swagger-ui", "v3", "error");
    private static final String SLASH = "/";

    private final TemplateEngine templateEngine;
    private final AppProperties appProperties;

    public static boolean isUnknownPage(final String method, final String path) {
        if (!SERVED_METHODS.contains(method) || !StringUtils.startsWith(path, SLASH)) {
            return false;
        }
        final var rest = path.substring(SLASH.length());
        final var firstSlash = rest.indexOf(SLASH);
        if (firstSlash < 0 || firstSlash == rest.length() - 1) {
            return false;
        }
        return !KEPT_FIRST_SEGMENTS.contains(rest.substring(0, firstSlash).toLowerCase(Locale.ROOT));
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !isUnknownPage(request.getMethod(), pathOf(request));
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain) throws IOException {
        log.info("Unknown page answered with the not-found page method={}", request.getMethod());
        LinkUnavailableView.of(templateEngine, appProperties.getFrontendUrl(), appProperties.getServerUrl(), pathOf(request).substring(SLASH.length()), null,
                        LinkUnavailableView.MISSING)
                .asPageOnly()
                .render(Map.of(), request, response);
    }

    private static String pathOf(final HttpServletRequest request) {
        return StringUtils.removeStart(request.getRequestURI(), request.getContextPath());
    }

}
