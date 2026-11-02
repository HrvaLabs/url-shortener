package cc.hrva.urlshortener.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

public final class OAuthChallenges {

    public static final String PARAMETER = "challenge";
    public static final String ATTRIBUTE = "hrva.oauth.challenge";

    private static final Pattern S256_CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OAuthChallenges() {}

    public static boolean wellFormed(final String challenge) {
        return challenge != null && S256_CHALLENGE.matcher(challenge).matches();
    }

    public static boolean matches(final String verifier, final String challenge) {
        if (verifier == null || !wellFormed(challenge)) {
            return false;
        }
        final var computed = ENCODER.encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII), challenge.getBytes(StandardCharsets.US_ASCII));
    }

    public static Optional<String> of(final HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String challenge ? Optional.of(challenge) : Optional.empty();
    }

    private static byte[] sha256(final byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
