package cc.hrva.urlshortener.mcp.bridge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class BridgedAuthorizationRequestFilter extends OncePerRequestFilter {

    public static final String AUTHORIZATION_QUERY_ATTRIBUTE = BridgedAuthorizationRequestFilter.class.getName() + ".AUTHORIZATION_QUERY";

    private final RequestMatcher authorizationEndpoint;
    private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final AuthenticationTrustResolver authenticationTrustResolver = new AuthenticationTrustResolverImpl();

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        if (authorizationEndpoint.matches(request) && isAuthenticated() && !isApprovedAuthorizationRequest(request)) {
            final var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            securityContextHolderStrategy.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAuthenticated() {
        return authenticationTrustResolver.isAuthenticated(securityContextHolderStrategy.getContext().getAuthentication());
    }

    private static boolean isApprovedAuthorizationRequest(final HttpServletRequest request) {
        final var session = request.getSession(false);
        final var queryString = request.getQueryString();

        return session != null && queryString != null && queryString.equals(session.getAttribute(AUTHORIZATION_QUERY_ATTRIBUTE));
    }

}
