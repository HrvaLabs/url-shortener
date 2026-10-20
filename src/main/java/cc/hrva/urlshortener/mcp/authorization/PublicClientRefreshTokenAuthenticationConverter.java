package cc.hrva.urlshortener.mcp.authorization;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

public class PublicClientRefreshTokenAuthenticationConverter implements AuthenticationConverter {

    private static final String REFRESH_TOKEN_GRANT = AuthorizationGrantType.REFRESH_TOKEN.getValue();

    @Override
    public Authentication convert(final HttpServletRequest request) {
        if (!isRefreshTokenRequestWithoutClientCredentials(request)) {
            return null;
        }

        final var clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
        if (clientIds == null || clientIds.length != 1 || !StringUtils.hasText(clientIds[0])) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }

        return new OAuth2ClientAuthenticationToken(clientIds[0], ClientAuthenticationMethod.NONE, null,
                Map.of(OAuth2ParameterNames.GRANT_TYPE, REFRESH_TOKEN_GRANT));
    }

    private static boolean isRefreshTokenRequestWithoutClientCredentials(final HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && REFRESH_TOKEN_GRANT.equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))
                && request.getHeader(HttpHeaders.AUTHORIZATION) == null
                && request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) == null
                && request.getParameter(OAuth2ParameterNames.CLIENT_ASSERTION) == null;
    }

}
