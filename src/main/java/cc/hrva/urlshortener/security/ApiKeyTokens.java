package cc.hrva.urlshortener.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.apache.commons.lang3.StringUtils;

public final class ApiKeyTokens {

    private static final String PREFIX = "hrva_";
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final BigInteger RADIX = BigInteger.valueOf(BASE62.length());
    private static final int RANDOM_BYTES = 32;
    private static final int ENCODED_LENGTH = 43;
    private static final int DISPLAY_CHARACTERS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyTokens() {
    }

    public static NewApiKey generate() {
        final var bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        final var key = PREFIX + base62(bytes);
        return new NewApiKey(key, hash(key), displayPrefix(key));
    }

    public static String hash(final String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static boolean sameHash(final String storedHash, final String computedHash) {
        return storedHash != null && computedHash != null
                && MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII), computedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private static String displayPrefix(final String key) {
        return StringUtils.left(key, PREFIX.length() + DISPLAY_CHARACTERS);
    }

    private static String base62(final byte[] bytes) {
        var value = new BigInteger(1, bytes);
        final var encoded = new StringBuilder();
        while (value.signum() > 0) {
            final var division = value.divideAndRemainder(RADIX);
            encoded.append(BASE62.charAt(division[1].intValue()));
            value = division[0];
        }
        return StringUtils.leftPad(encoded.reverse().toString(), ENCODED_LENGTH, BASE62.charAt(0));
    }

    public record NewApiKey(String key, String hash, String prefix) {

        @Override
        public String toString() {
            return "NewApiKey[prefix=" + prefix + "]";
        }

    }

}
