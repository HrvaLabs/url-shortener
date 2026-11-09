package cc.hrva.urlshortener.mcp.authorization;

import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;

@RequiredArgsConstructor
public class McpResources {

    public static final String RESOURCE_PARAMETER = "resource";

    private final String defaultResource;
    private final Set<String> resources;

    public boolean serves(final Object resource) {
        return resources.contains(resource);
    }

    public String grantedBy(final OAuth2Authorization authorization) {
        return Optional.ofNullable(authorization)
                .map(signIn -> signIn.<OAuth2AuthorizationRequest>getAttribute(OAuth2AuthorizationRequest.class.getName()))
                .map(authorizationRequest -> authorizationRequest.getAdditionalParameters().get(RESOURCE_PARAMETER))
                .filter(resources::contains)
                .map(String.class::cast)
                .orElse(defaultResource);
    }

}
