package cc.hrva.urlshortener.mcp.authorization;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

public class RefreshAudienceCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final String RESOURCE_PARAMETER = "resource";

    @Override
    public void customize(final JwtEncodingContext context) {
        if (!isAccessTokenRefreshWithoutResource(context) || context.getAuthorization() == null) {
            return;
        }

        final var previousAccessToken = context.getAuthorization().getAccessToken();
        if (previousAccessToken == null || previousAccessToken.getClaims() == null) {
            return;
        }

        final var previousAudience = previousAccessToken.getClaims().get(JwtClaimNames.AUD);
        if (previousAudience != null) {
            context.getClaims().claim(JwtClaimNames.AUD, previousAudience);
        }
    }

    private static boolean isAccessTokenRefreshWithoutResource(final JwtEncodingContext context) {
        return OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                && AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType())
                && context.getAuthorizationGrant() instanceof OAuth2AuthorizationGrantAuthenticationToken grant
                && grant.getAdditionalParameters().get(RESOURCE_PARAMETER) == null;
    }

}
