package cc.hrva.urlshortener.mcp.authorization;

import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@RequiredArgsConstructor
public class AccessTokenAudienceCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final String RESOURCE_PARAMETER = "resource";

    private final String resource;

    @Override
    public void customize(final JwtEncodingContext context) {
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType()) && !requestsResource(context)) {
            context.getClaims().claim(JwtClaimNames.AUD, Collections.singletonList(resource));
        }
    }

    private static boolean requestsResource(final JwtEncodingContext context) {
        return context.getAuthorizationGrant() instanceof OAuth2AuthorizationGrantAuthenticationToken grant
                && grant.getAdditionalParameters().get(RESOURCE_PARAMETER) != null;
    }

}
