package cc.hrva.urlshortener.mcp.authorization;

import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationToken;

public class DcrScopeValidator implements Consumer<OAuth2ClientRegistrationAuthenticationContext> {

    private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc7591#section-3.2.2";
    private static final Pattern SCOPE_TOKEN_WITHOUT_COMMA = Pattern.compile("[\\x21\\x23-\\x2B\\x2D-\\x5B\\x5D-\\x7E]+");

    @Override
    public void accept(final OAuth2ClientRegistrationAuthenticationContext authenticationContext) {
        final OAuth2ClientRegistrationAuthenticationToken authentication = authenticationContext.getAuthentication();
        final var scopes = authentication.getClientRegistration().getScopes();

        if (scopes != null && !scopes.stream().allMatch(DcrScopeValidator::isAllowed)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_SCOPE, "Invalid Client Registration: scope", ERROR_URI));
        }
    }

    private static boolean isAllowed(final String scope) {
        return scope != null
                && SCOPE_TOKEN_WITHOUT_COMMA.matcher(scope).matches()
                && !OidcScopes.OPENID.equals(scope);
    }

}
