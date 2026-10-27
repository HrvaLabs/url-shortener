package cc.hrva.urlshortener.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP (HmacSHA1, 6 digits, 30s step) with a self-contained Base32 codec.
 * No external dependencies.
 */
public final class Totp {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;
    private static final int DIGITS = 6;
    private static final long TIME_STEP_SECONDS = 30L;
    private static final int WINDOW = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    public static String generateSecret() {
        final var bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public static boolean verify(final String base32Secret, final String code) {
        if (base32Secret == null || code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        final var key = base32Decode(base32Secret);
        final var currentStep = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS;
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            if (code.equals(generateCode(key, currentStep + offset))) {
                return true;
            }
        }
        return false;
    }

    public static String buildOtpAuthUri(final String secret, final String accountEmail, final String issuer) {
        final var encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        final var label = URLEncoder.encode(issuer + ":" + accountEmail, StandardCharsets.UTF_8);
        return String.format("otpauth://totp/%s?secret=%s&issuer=%s&digits=%d&period=%d",
                label, secret, encodedIssuer, DIGITS, TIME_STEP_SECONDS);
    }

    public static String currentCode(final String base32Secret) {
        final var step = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS;
        return generateCode(base32Decode(base32Secret), step);
    }

    private static String generateCode(final byte[] key, final long step) {
        final var data = new byte[8];
        var value = step;
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        try {
            final var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            final var hash = mac.doFinal(data);
            final var binaryOffset = hash[hash.length - 1] & 0x0F;
            final var truncated = ((hash[binaryOffset] & 0x7F) << 24)
                    | ((hash[binaryOffset + 1] & 0xFF) << 16)
                    | ((hash[binaryOffset + 2] & 0xFF) << 8)
                    | (hash[binaryOffset + 3] & 0xFF);
            final var otp = truncated % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate TOTP code", e);
        }
    }

    static String base32Encode(final byte[] data) {
        final var result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (final byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                result.append(BASE32_ALPHABET.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            result.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return result.toString();
    }

    static byte[] base32Decode(final String encoded) {
        final var clean = encoded.trim().replace("=", "").toUpperCase(Locale.ROOT);
        final var output = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (final char c : clean.toCharArray()) {
            final var index = BASE32_ALPHABET.indexOf(c);
            if (index < 0) {
                continue;
            }
            buffer = (buffer << 5) | index;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                output.write((buffer >> bitsLeft) & 0xFF);
            }
        }
        return output.toByteArray();
    }

}
