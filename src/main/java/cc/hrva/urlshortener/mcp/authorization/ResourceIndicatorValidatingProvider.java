package cc.hrva.urlshortener.mcp.authorization;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;

@RequiredArgsConstructor
public class ResourceIndicatorValidatingProvider implements AuthenticationProvider {

    private static final String INVALID_TARGET_CODE = "invalid_target";
    private static final String RFC_8707 = "https://datatracker.ietf.org/doc/html/rfc8707#section-2";
    private static final OAuth2Error INVALID_TARGET = new OAuth2Error(
            INVALID_TARGET_CODE,
            "The requested resource is not served by this authorization server",
            RFC_8707
    );
    private static final OAuth2Error NOT_GRANTED = new OAuth2Error(
            INVALID_TARGET_CODE,
            "The requested resource is not the one this sign-in was granted for",
            RFC_8707
    );
    private static final OAuth2TokenType AUTHORIZATION_CODE = new OAuth2TokenType(OAuth2ParameterNames.CODE);

    private final AuthenticationProvider delegate;
    private final McpResources mcpResources;
    private final OAuth2AuthorizationService authorizationService;

    @Override
    public Authentication authenticate(final Authentication authentication) {
        if (authentication instanceof OAuth2AuthorizationGrantAuthenticationToken grant) {
            final var requestedResource = grant.getAdditionalParameters().get(McpResources.RESOURCE_PARAMETER);
            if (requestedResource != null && !mcpResources.serves(requestedResource)) {
                throw new OAuth2AuthenticationException(INVALID_TARGET);
            }
            if (requestedResource != null && grantedForAnotherResource(grant, requestedResource)) {
                throw new OAuth2AuthenticationException(NOT_GRANTED);
            }
        }

        return delegate.authenticate(authentication);
    }

    @Override
    public boolean supports(final Class<?> authentication) {
        return delegate.supports(authentication);
    }

    private boolean grantedForAnotherResource(final OAuth2AuthorizationGrantAuthenticationToken grant, final Object requestedResource) {
        return signInOf(grant)
                .map(mcpResources::grantedBy)
                .filter(granted -> !granted.equals(requestedResource))
                .isPresent();
    }

    private Optional<OAuth2Authorization> signInOf(final OAuth2AuthorizationGrantAuthenticationToken grant) {
        if (grant instanceof OAuth2AuthorizationCodeAuthenticationToken codeGrant) {
            return Optional.ofNullable(authorizationService.findByToken(codeGrant.getCode(), AUTHORIZATION_CODE));
        }
        if (grant instanceof OAuth2RefreshTokenAuthenticationToken refreshGrant) {
            return Optional.ofNullable(authorizationService.findByToken(refreshGrant.getRefreshToken(), OAuth2TokenType.REFRESH_TOKEN));
        }

        return Optional.empty();
    }

}
