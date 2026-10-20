package cc.hrva.urlshortener.mcp.authorization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

@Slf4j
@RequiredArgsConstructor
public class StillAuthorizedValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error NO_LONGER_AUTHORIZED = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN,
            "The access token is no longer authorized",
            null
    );
    private static final OAuth2Error NOT_CHECKED = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN,
            "The access token could not be checked",
            null
    );

    private final OAuth2AuthorizationService authorizationService;

    @Override
    public OAuth2TokenValidatorResult validate(final Jwt jwt) {
        final OAuth2Authorization authorization;
        try {
            authorization = authorizationService.findByToken(jwt.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
        } catch (final DataAccessException exception) {
            log.warn("Could not check whether an MCP access token is still authorized: {}", exception.getClass().getSimpleName());
            return OAuth2TokenValidatorResult.failure(NOT_CHECKED);
        }

        if (authorization == null || authorization.getAccessToken() == null || !authorization.getAccessToken().isActive()) {
            return OAuth2TokenValidatorResult.failure(NO_LONGER_AUTHORIZED);
        }

        return OAuth2TokenValidatorResult.success();
    }

}
