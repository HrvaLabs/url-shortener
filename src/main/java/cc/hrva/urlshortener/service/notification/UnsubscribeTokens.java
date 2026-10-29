package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.model.enums.NotificationType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UnsubscribeTokens {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = ".";
    private static final int MAX_TOKEN_LENGTH = 300;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final NotificationKeys keys;

    public String issue(final long userId, final NotificationType type) {
        if (!type.isEmail()) {
            throw new IllegalArgumentException("Only email notification types have an unsubscribe link");
        }

        final var payload = VERSION + SEPARATOR + userId + SEPARATOR + type.name();
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + SEPARATOR + signature(keys.unsubscribeKey(), payload);
    }

    public Optional<UnsubscribeTarget> read(final String token) {
        if (StringUtils.isEmpty(token) || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }

        final var parts = StringUtils.splitPreserveAllTokens(token, SEPARATOR);
        if (parts.length != 2) {
            return Optional.empty();
        }

        final String payload;
        final byte[] signature;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            signature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }

        final var expected = NotificationKeys.hmac(keys.unsubscribeKey(), payload.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, signature)) {
            return Optional.empty();
        }

        return targetOf(payload);
    }

    static String signature(final byte[] key, final String payload) {
        return ENCODER.encodeToString(NotificationKeys.hmac(key, payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static Optional<UnsubscribeTarget> targetOf(final String payload) {
        final var fields = StringUtils.splitPreserveAllTokens(payload, SEPARATOR);
        if (fields.length != 3 || !VERSION.equals(fields[0]) || !NumberUtils.isDigits(fields[1])) {
            return Optional.empty();
        }

        final var type = EnumUtils.getEnum(NotificationType.class, fields[2]);
        if (type == null || !type.isEmail()) {
            return Optional.empty();
        }

        try {
            return Optional.of(new UnsubscribeTarget(Long.parseLong(fields[1]), type));
        } catch (final NumberFormatException exception) {
            return Optional.empty();
        }
    }

}
