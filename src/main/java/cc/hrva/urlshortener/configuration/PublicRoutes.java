package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.controller.ReportController;
import cc.hrva.urlshortener.controller.UnsubscribeController;
import cc.hrva.urlshortener.security.ApiKeyRateLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;

final class PublicRoutes {

    static final List<String> HEALTH_PROBES = List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");
    static final List<String> SIGNED_IN_UNDER_A_PUBLIC_PATTERN = List.of("/api/v1/urls/export", "/api/v1/urls/tags", "/api/v1/urls/all");
    static final String LINK_EDIT = "/api/v1/urls/*";

    private static final List<PublicRoute> ROUTES = List.of(
            get("/"),
            get("/*"),
            get("/*/"),
            get("/.well-known/security.txt"),
            get("/.well-known/openai-apps-challenge"),
            get("/api/changelog"),
            get("/api/v1/public/settings"),
            get("/api/v1/public/stats"),
            get("/api/v1/public/alias-check"),
            get("/api/v1/public/bio-pages/*"),
            post("/api/v1/public/bio-pages/*/views"),
            post(ReportController.REPORTS_ENDPOINT),
            get(UnsubscribeController.UNSUBSCRIBE_ENDPOINT),
            post(UnsubscribeController.UNSUBSCRIBE_ENDPOINT),
            post("/api/v1/urls"),
            get("/api/v1/urls/*"),
            get("/api/v1/urls/*/peek"),
            post("/api/v1/urls/*/unlock"),
            get("/api/v1/urls/*/preview"),
            get("/api/v1/urls/*/qr"),
            post("/api/v1/auth/register"),
            post("/api/v1/auth/login"),
            post("/api/v1/auth/password-reset"),
            post("/api/v1/auth/password-reset/confirm"),
            post("/api/v1/auth/verify-email/confirm"),
            post("/api/v1/auth/verify-email/resend"),
            post("/api/v1/auth/oauth/exchange"),
            post("/api/v1/auth/passkeys/options"),
            post("/api/v1/auth/passkeys/verify"),
            post("/api/v1/auth/2fa/totp"),
            post("/api/v1/auth/2fa/webauthn/options"),
            post("/api/v1/auth/2fa/webauthn/verify"),
            get("/oauth2/authorization/*"),
            get("/login/oauth2/code/*"),
            get("/swagger-ui.html"),
            get("/swagger-ui/**"),
            get("/v3/api-docs"),
            get("/v3/api-docs/**")
    );

    private PublicRoutes() {}

    static void permit(final AuthorizeHttpRequestsConfigurer<?>.AuthorizationManagerRequestMatcherRegistry registry) {
        ROUTES.stream()
                .flatMap(PublicRoute::withHead)
                .forEach(route -> registry.requestMatchers(route.method(), route.pattern()).permitAll());
        registry.requestMatchers(new AndRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.PUT, LINK_EDIT),
                PublicRoutes::carriesApiKey)).permitAll();
    }

    private static boolean carriesApiKey(final HttpServletRequest request) {
        return StringUtils.isNotBlank(request.getHeader(ApiKeyRateLimitFilter.API_KEY_HEADER));
    }

    private static PublicRoute get(final String pattern) {
        return new PublicRoute(HttpMethod.GET, pattern);
    }

    private static PublicRoute post(final String pattern) {
        return new PublicRoute(HttpMethod.POST, pattern);
    }

    private record PublicRoute(HttpMethod method, String pattern) {

        private Stream<PublicRoute> withHead() {
            return HttpMethod.GET.equals(method) ? Stream.of(this, new PublicRoute(HttpMethod.HEAD, pattern)) : Stream.of(this);
        }

    }

}
