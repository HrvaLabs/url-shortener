package cc.hrva.urlshortener.mcp.authorization;

import java.util.function.Consumer;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationToken;

public class DcrRedirectUriValidator implements Consumer<OAuth2ClientRegistrationAuthenticationContext> {

    private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc7591#section-3.2.2";
    private static final String INVALID_REDIRECT_URI = "invalid_redirect_uri";
    private static final String STORAGE_DELIMITER = ",";

    @Override
    public void accept(final OAuth2ClientRegistrationAuthenticationContext authenticationContext) {
        final OAuth2ClientRegistrationAuthenticationToken authentication = authenticationContext.getAuthentication();
        final var redirectUris = authentication.getClientRegistration().getRedirectUris();

        if (redirectUris != null && redirectUris.stream().anyMatch(DcrRedirectUriValidator::isRejected)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(INVALID_REDIRECT_URI, "Invalid Client Registration: redirect_uris", ERROR_URI));
        }
    }

    private static boolean isRejected(final String redirectUri) {
        return redirectUri == null || redirectUri.contains(STORAGE_DELIMITER);
    }

}
