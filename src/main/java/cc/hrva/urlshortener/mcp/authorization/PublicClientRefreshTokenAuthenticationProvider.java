package cc.hrva.urlshortener.mcp.authorization;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@RequiredArgsConstructor
public class PublicClientRefreshTokenAuthenticationProvider implements AuthenticationProvider {

    private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-3.2.1";

    private final RegisteredClientRepository registeredClientRepository;

    @Override
    public Authentication authenticate(final Authentication authentication) {
        final var clientAuthentication = (OAuth2ClientAuthenticationToken) authentication;
        if (!isPublicClientRefresh(clientAuthentication)) {
            return null;
        }

        final var registeredClient = registeredClientRepository.findByClientId(clientAuthentication.getPrincipal().toString());
        if (registeredClient == null) {
            throw invalidClient(OAuth2ParameterNames.CLIENT_ID);
        }
        if (!registeredClient.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw invalidClient("authentication_method");
        }

        return new OAuth2ClientAuthenticationToken(registeredClient, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(final Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static boolean isPublicClientRefresh(final OAuth2ClientAuthenticationToken clientAuthentication) {
        return ClientAuthenticationMethod.NONE.equals(clientAuthentication.getClientAuthenticationMethod())
                && AuthorizationGrantType.REFRESH_TOKEN.getValue()
                .equals(clientAuthentication.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE));
    }

    private static OAuth2AuthenticationException invalidClient(final String parameterName) {
        return new OAuth2AuthenticationException(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT, "Client authentication failed: " + parameterName, ERROR_URI));
    }

}
