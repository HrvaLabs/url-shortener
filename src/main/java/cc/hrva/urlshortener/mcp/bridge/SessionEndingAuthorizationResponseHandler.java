package cc.hrva.urlshortener.mcp.bridge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

public class SessionEndingAuthorizationResponseHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationSuccess(final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final Authentication authentication) throws IOException {
        endSession(request);

        final var authorizationResponse = (OAuth2AuthorizationCodeRequestAuthenticationToken) authentication;
        final var redirectUri = UriComponentsBuilder.fromUriString(authorizationResponse.getRedirectUri())
                .queryParam(OAuth2ParameterNames.CODE, authorizationResponse.getAuthorizationCode().getTokenValue());
        addState(redirectUri, authorizationResponse.getState());

        redirectStrategy.sendRedirect(request, response, redirectUri.build(true).toUriString());
    }

    @Override
    public void onAuthenticationFailure(final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final AuthenticationException exception) throws IOException {
        endSession(request);

        final var error = exception instanceof OAuth2AuthenticationException oauth2Exception
                ? oauth2Exception.getError()
                : new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR);
        final var authorizationRequest = exception instanceof OAuth2AuthorizationCodeRequestAuthenticationException authorizationException
                ? authorizationException.getAuthorizationCodeRequestAuthentication()
                : null;
        if (authorizationRequest == null || !StringUtils.hasText(authorizationRequest.getRedirectUri())) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), error.toString());
            return;
        }

        final var redirectUri = UriComponentsBuilder.fromUriString(authorizationRequest.getRedirectUri())
                .queryParam(OAuth2ParameterNames.ERROR, error.getErrorCode());
        if (StringUtils.hasText(error.getDescription())) {
            redirectUri.queryParam(OAuth2ParameterNames.ERROR_DESCRIPTION, UriUtils.encode(error.getDescription(), StandardCharsets.UTF_8));
        }
        if (StringUtils.hasText(error.getUri())) {
            redirectUri.queryParam(OAuth2ParameterNames.ERROR_URI, UriUtils.encode(error.getUri(), StandardCharsets.UTF_8));
        }
        addState(redirectUri, authorizationRequest.getState());

        redirectStrategy.sendRedirect(request, response, redirectUri.build(true).toUriString());
    }

    private static void endSession(final HttpServletRequest request) {
        final var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private static void addState(final UriComponentsBuilder redirectUri, final String state) {
        if (StringUtils.hasText(state)) {
            redirectUri.queryParam(OAuth2ParameterNames.STATE, UriUtils.encode(state, StandardCharsets.UTF_8));
        }
    }

}
