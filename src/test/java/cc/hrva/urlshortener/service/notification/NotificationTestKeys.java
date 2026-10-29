package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import org.springframework.mock.env.MockEnvironment;

public final class NotificationTestKeys {

    public static final String KEY = "dGVzdC1vbmx5LW5vdGlmaWNhdGlvbnMta2V5LTMyLWJ5dGVzIQ==";
    public static final String PROPERTY = "app.notifications.key=" + KEY;

    private NotificationTestKeys() {
    }

    public static NotificationKeys keys() {
        return keys(KEY);
    }

    public static NotificationKeys keys(final String key) {
        final var appProperties = new AppProperties();
        appProperties.getNotifications().setKey(key);
        final var keys = new NotificationKeys(appProperties, new MockEnvironment());
        keys.init();
        return keys;
    }

}
