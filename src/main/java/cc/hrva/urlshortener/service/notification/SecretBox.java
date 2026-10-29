package cc.hrva.urlshortener.service.notification;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecretBox {

    private static final String PREFIX = "v1:";
    private static final String CONTEXT_PREFIX = "notification-channel:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final String CANNOT_OPEN = "sealed value can't be opened";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NotificationKeys keys;

    public static String contextFor(final long userId) {
        return CONTEXT_PREFIX + userId;
    }

    public String seal(final String plain, final String context) {
        final var iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            final var cipher = cipher(Cipher.ENCRYPT_MODE, iv, context);
            final var sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array());
        } catch (final GeneralSecurityException exception) {
            throw new IllegalStateException("value can't be sealed");
        }
    }

    public String open(final String sealed, final String context) {
        if (sealed == null || !sealed.startsWith(PREFIX)) {
            throw new IllegalStateException(CANNOT_OPEN);
        }
        try {
            final var bytes = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
            if (bytes.length < IV_BYTES + TAG_BYTES) {
                throw new IllegalStateException(CANNOT_OPEN);
            }
            final var cipher = cipher(Cipher.DECRYPT_MODE, java.util.Arrays.copyOf(bytes, IV_BYTES), context);
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (final GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(CANNOT_OPEN);
        }
    }

    private Cipher cipher(final int mode, final byte[] iv, final String context) throws GeneralSecurityException {
        final var cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, new SecretKeySpec(keys.channelSecretsKey(), ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

}
