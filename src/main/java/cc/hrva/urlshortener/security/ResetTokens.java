package cc.hrva.urlshortener.security;

import java.security.SecureRandom;
import java.util.Base64;

public final class ResetTokens {

    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ResetTokens() {
    }

    public static String generate() {
        final var bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(final String token) {
        return ApiKeyTokens.hash(token);
    }

}
