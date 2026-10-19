package cc.hrva.urlshortener.mcp.authorization;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

@RequiredArgsConstructor
public class StillAuthorizedValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error NO_LONGER_AUTHORIZED = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN,
            "The access token is no longer authorized",
            null
    );

    private final OAuth2AuthorizationService authorizationService;

    @Override
    public OAuth2TokenValidatorResult validate(final Jwt jwt) {
        final var authorization = authorizationService.findByToken(jwt.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);

        if (authorization == null || authorization.getAccessToken() == null || !authorization.getAccessToken().isActive()) {
            return OAuth2TokenValidatorResult.failure(NO_LONGER_AUTHORIZED);
        }

        return OAuth2TokenValidatorResult.success();
    }

}
