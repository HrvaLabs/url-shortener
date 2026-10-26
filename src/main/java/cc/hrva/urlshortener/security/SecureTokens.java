package cc.hrva.urlshortener.security;

import java.security.SecureRandom;
import java.util.Base64;

public final class SecureTokens {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SecureTokens() {}

    public static String random() {
        final var bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);

        return TOKEN_ENCODER.encodeToString(bytes);
    }

}
