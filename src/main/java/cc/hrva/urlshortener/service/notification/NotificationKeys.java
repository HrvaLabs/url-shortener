package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationKeys {

    public static final String LOCAL_DEV_KEY = "bG9jYWwtZGV2LW5vdGlmaWNhdGlvbnMta2V5LTMyYnl0ZXMhIQ==";

    static final String HMAC = "HmacSHA256";

    private static final int MIN_KEY_BYTES = 32;
    private static final String LOCAL_PROFILE = "local";
    private static final String CHANNEL_SECRETS_LABEL = "hrva-channel-secrets-v1";
    private static final String UNSUBSCRIBE_LABEL = "hrva-unsubscribe-v1";
    private static final String REFUSAL = "APP_NOTIFICATIONS_KEY must be set to a random 32-byte base64 value";

    private final AppProperties appProperties;
    private final Environment environment;

    private byte[] channelSecretsKey;
    private byte[] unsubscribeKey;

    @PostConstruct
    public void init() {
        final var key = StringUtils.strip(appProperties.getNotifications().getKey());
        if (StringUtils.isEmpty(key)) {
            throw new IllegalStateException(REFUSAL);
        }
        if (LOCAL_DEV_KEY.equals(key) && !environment.matchesProfiles(LOCAL_PROFILE)) {
            throw new IllegalStateException(REFUSAL);
        }

        final var master = decoded(key);
        if (master.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(REFUSAL);
        }

        channelSecretsKey = derived(master, CHANNEL_SECRETS_LABEL);
        unsubscribeKey = derived(master, UNSUBSCRIBE_LABEL);
    }

    public byte[] channelSecretsKey() {
        return channelSecretsKey.clone();
    }

    public byte[] unsubscribeKey() {
        return unsubscribeKey.clone();
    }

    static byte[] hmac(final byte[] key, final byte[] data) {
        try {
            final var mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (final GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 isn't available");
        }
    }

    private static byte[] decoded(final String key) {
        try {
            return Base64.getDecoder().decode(key);
        } catch (final IllegalArgumentException exception) {
            throw new IllegalStateException(REFUSAL);
        }
    }

    private static byte[] derived(final byte[] master, final String label) {
        return hmac(master, label.getBytes(StandardCharsets.UTF_8));
    }

}
