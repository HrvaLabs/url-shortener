package cc.hrva.urlshortener.mcp.authorization;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@RequiredArgsConstructor
public class AccessTokenAudienceCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final String RESOURCE_PARAMETER = "resource";

    private final String defaultResource;
    private final Set<String> resources;

    @Override
    public void customize(final JwtEncodingContext context) {
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType()) && !requestsResource(context)) {
            context.getClaims().claim(JwtClaimNames.AUD, Collections.singletonList(authorizedResource(context)));
        }
    }

    private String authorizedResource(final JwtEncodingContext context) {
        return Optional.ofNullable(context.getAuthorization())
                .map(authorization -> authorization.<OAuth2AuthorizationRequest>getAttribute(OAuth2AuthorizationRequest.class.getName()))
                .map(authorizationRequest -> authorizationRequest.getAdditionalParameters().get(RESOURCE_PARAMETER))
                .filter(resources::contains)
                .map(String.class::cast)
                .orElse(defaultResource);
    }

    private static boolean requestsResource(final JwtEncodingContext context) {
        return context.getAuthorizationGrant() instanceof OAuth2AuthorizationGrantAuthenticationToken grant
                && grant.getAdditionalParameters().get(RESOURCE_PARAMETER) != null;
    }

}
