package cc.hrva.urlshortener.mcp.authorization;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationToken;

public class DcrMetadataSizeValidator implements Consumer<OAuth2ClientRegistrationAuthenticationContext> {

    private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc7591#section-3.2.2";
    private static final String INVALID_CLIENT_METADATA = "invalid_client_metadata";
    private static final String STORAGE_DELIMITER = ",";
    private static final int MAX_CLIENT_NAME_LENGTH = 100;
    private static final int MAX_REDIRECT_URIS = 5;
    private static final int REDIRECT_URIS_COLUMN_LENGTH = 1000;
    private static final int SCOPES_COLUMN_LENGTH = 1000;
    private static final int MAX_JWKS_URI_LENGTH = 1000;

    @Override
    public void accept(final OAuth2ClientRegistrationAuthenticationContext authenticationContext) {
        final OAuth2ClientRegistrationAuthenticationToken authentication = authenticationContext.getAuthentication();
        final var clientRegistration = authentication.getClientRegistration();
        final var clientName = clientRegistration.getClientName();
        final var redirectUris = clientRegistration.getRedirectUris();
        final var jwkSetUrl = clientRegistration.getJwkSetUrl();

        if (clientName != null && clientName.length() > MAX_CLIENT_NAME_LENGTH) {
            reject("client_name");
        }
        if (redirectUris != null && (redirectUris.size() > MAX_REDIRECT_URIS || storedLength(redirectUris) > REDIRECT_URIS_COLUMN_LENGTH)) {
            reject("redirect_uris");
        }
        if (storedLength(clientRegistration.getScopes()) > SCOPES_COLUMN_LENGTH) {
            reject("scope");
        }
        if (jwkSetUrl != null && jwkSetUrl.toString().length() > MAX_JWKS_URI_LENGTH) {
            reject("jwks_uri");
        }
    }

    private static int storedLength(final Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }

        return values.stream()
                .mapToInt(value -> Objects.toString(value, "").length())
                .sum() + (values.size() - 1) * STORAGE_DELIMITER.length();
    }

    private static void reject(final String fieldName) {
        throw new OAuth2AuthenticationException(
                new OAuth2Error(INVALID_CLIENT_METADATA, "Invalid Client Registration: " + fieldName, ERROR_URI));
    }

}
