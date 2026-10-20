package cc.hrva.urlshortener.mcp.authorization;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

@RequiredArgsConstructor
public class ResourceIndicatorValidatingProvider implements AuthenticationProvider {

    private static final String RESOURCE_PARAMETER = "resource";
    private static final OAuth2Error INVALID_TARGET = new OAuth2Error(
            "invalid_target",
            "The requested resource is not served by this authorization server",
            "https://datatracker.ietf.org/doc/html/rfc8707#section-2"
    );

    private final AuthenticationProvider delegate;
    private final String resource;

    @Override
    public Authentication authenticate(final Authentication authentication) {
        if (authentication instanceof OAuth2AuthorizationGrantAuthenticationToken grant) {
            final var requestedResource = grant.getAdditionalParameters().get(RESOURCE_PARAMETER);
            if (requestedResource != null && !resource.equals(requestedResource)) {
                throw new OAuth2AuthenticationException(INVALID_TARGET);
            }
        }

        return delegate.authenticate(authentication);
    }

    @Override
    public boolean supports(final Class<?> authentication) {
        return delegate.supports(authentication);
    }

}
