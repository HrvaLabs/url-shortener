package cc.hrva.urlshortener.mcp.authorization;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationToken;

public class DcrGrantTypeValidator implements Consumer<OAuth2ClientRegistrationAuthenticationContext> {

    private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc7591#section-3.2.2";
    private static final String INVALID_CLIENT_METADATA = "invalid_client_metadata";
    private static final Set<String> ALLOWED_GRANT_TYPES = Set.of(
            AuthorizationGrantType.AUTHORIZATION_CODE.getValue(),
            AuthorizationGrantType.REFRESH_TOKEN.getValue()
    );
    private static final Set<String> ALLOWED_RESPONSE_TYPES = Set.of(OAuth2AuthorizationResponseType.CODE.getValue());

    @Override
    public void accept(final OAuth2ClientRegistrationAuthenticationContext authenticationContext) {
        final OAuth2ClientRegistrationAuthenticationToken authentication = authenticationContext.getAuthentication();
        final var clientRegistration = authentication.getClientRegistration();

        rejectUnless(clientRegistration.getGrantTypes(), ALLOWED_GRANT_TYPES, "grant_types");
        rejectUnless(clientRegistration.getResponseTypes(), ALLOWED_RESPONSE_TYPES, "response_types");
    }

    private static void rejectUnless(final List<String> requested, final Set<String> allowed, final String fieldName) {
        if (requested != null && !allowed.containsAll(requested)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(INVALID_CLIENT_METADATA, "Invalid Client Registration: " + fieldName, ERROR_URI));
        }
    }

}
