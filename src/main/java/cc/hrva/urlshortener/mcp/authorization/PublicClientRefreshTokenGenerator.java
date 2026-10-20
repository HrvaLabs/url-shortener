package cc.hrva.urlshortener.mcp.authorization;

import java.time.Instant;
import java.util.Base64;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

public class PublicClientRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private static final int REFRESH_TOKEN_BYTES = 96;

    private final StringKeyGenerator refreshTokenValueGenerator =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), REFRESH_TOKEN_BYTES);

    @Override
    public OAuth2RefreshToken generate(final OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType()) || !isPublicClient(context)) {
            return null;
        }

        final var issuedAt = Instant.now();
        final var expiresAt = issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());

        return new OAuth2RefreshToken(refreshTokenValueGenerator.generateKey(), issuedAt, expiresAt);
    }

    private static boolean isPublicClient(final OAuth2TokenContext context) {
        final var authorizationGrant = context.getAuthorizationGrant();

        return authorizationGrant != null
                && authorizationGrant.getPrincipal() instanceof OAuth2ClientAuthenticationToken clientAuthentication
                && ClientAuthenticationMethod.NONE.equals(clientAuthentication.getClientAuthenticationMethod());
    }

}
