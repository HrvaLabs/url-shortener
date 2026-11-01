package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.util.ProblemResponses;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

public final class AccessAnswers {

    public static final String SIGN_IN_TO_CONTINUE = "Sign in to continue.";
    public static final String ACCESS_DENIED = "Access denied";

    private AccessAnswers() {}

    public static AuthenticationEntryPoint notSignedIn() {
        return (request, response, exception) -> ProblemResponses.write(request, response, HttpStatus.UNAUTHORIZED, SIGN_IN_TO_CONTINUE);
    }

    public static AccessDeniedHandler denied() {
        return (request, response, exception) -> ProblemResponses.write(request, response, HttpStatus.FORBIDDEN, ACCESS_DENIED);
    }

    public static boolean nobodySignedIn() {
        final var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated();
    }

}
