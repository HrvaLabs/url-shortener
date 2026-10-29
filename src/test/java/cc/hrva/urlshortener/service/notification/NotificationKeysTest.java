package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationKeysTest {

    private static final String REFUSAL = "APP_NOTIFICATIONS_KEY must be set to a random 32-byte base64 value";
    private static final String SHORT_KEY = "c2hvcnQta2V5LW9mLTI0LWJ5dGVzISEh";

    @Test
    void devKeyIsRefusedOutsideLocal() {
        assertThatThrownBy(() -> started(NotificationKeys.LOCAL_DEV_KEY, "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REFUSAL);
        assertThatThrownBy(() -> started(NotificationKeys.LOCAL_DEV_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REFUSAL);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankKeyIsRefusedOutsideLocal(final String key) {
        assertThatThrownBy(() -> started(key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REFUSAL);
    }

    @Test
    void shortKeyIsRefused() {
        assertThatThrownBy(() -> started(SHORT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REFUSAL);
        assertThatThrownBy(() -> started(SHORT_KEY, "local"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REFUSAL);
    }

    @Test
    void aKeyThatIsNotBase64IsRefused() {
        assertThatThrownBy(() -> started("not base64 at all, but long enough to pass a length check"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REFUSAL)
                .hasNoCause();
    }

    @Test
    void devKeyWorksInLocal() {
        final var keys = started(NotificationKeys.LOCAL_DEV_KEY, "local");

        assertThat(keys.channelSecretsKey()).hasSize(32);
        assertThat(keys.unsubscribeKey()).hasSize(32);
    }

    @Test
    void subkeysDifferAndAreStable() {
        final var first = started(NotificationTestKeys.KEY);
        final var second = started(NotificationTestKeys.KEY);
        final var other = started("b3RoZXItbm90aWZpY2F0aW9ucy1rZXktb2YtMzItYnl0ZXMhIQ==");

        assertThat(first.channelSecretsKey()).isEqualTo(second.channelSecretsKey()).hasSize(32);
        assertThat(first.unsubscribeKey()).isEqualTo(second.unsubscribeKey()).hasSize(32);
        assertThat(first.channelSecretsKey()).isNotEqualTo(first.unsubscribeKey());
        assertThat(other.channelSecretsKey()).isNotEqualTo(first.channelSecretsKey());
        assertThat(other.unsubscribeKey()).isNotEqualTo(first.unsubscribeKey());
    }

    @Test
    void theKeysHandedOutCannotChangeTheStoredOnes() {
        final var keys = started(NotificationTestKeys.KEY);
        final var expected = keys.channelSecretsKey().clone();

        keys.channelSecretsKey()[0] ^= 1;

        assertThat(keys.channelSecretsKey()).isEqualTo(expected);
    }

    private static NotificationKeys started(final String key, final String... profiles) {
        final var appProperties = new AppProperties();
        appProperties.getNotifications().setKey(key);
        final var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        final var keys = new NotificationKeys(appProperties, environment);
        keys.init();
        return keys;
    }

}
