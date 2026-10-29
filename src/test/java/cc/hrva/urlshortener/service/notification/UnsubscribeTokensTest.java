package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.model.enums.NotificationType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsubscribeTokensTest {

    private final UnsubscribeTokens tokens = new UnsubscribeTokens(NotificationTestKeys.keys());

    @ParameterizedTest
    @EnumSource(value = NotificationType.class, names = {"DEAD_LINK_EMAIL", "WEEKLY_SUMMARY_EMAIL", "EXPIRY_REMINDER_EMAIL"})
    void roundTrips(final NotificationType type) {
        assertThat(tokens.read(tokens.issue(42L, type))).contains(new UnsubscribeTarget(42L, type));
    }

    @Test
    void theSameTargetGivesTheSameToken() {
        assertThat(tokens.issue(42L, NotificationType.WEEKLY_SUMMARY_EMAIL)).isEqualTo(tokens.issue(42L, NotificationType.WEEKLY_SUMMARY_EMAIL));
    }

    @Test
    void aChangedTypeOrUserFailsTheSignature() {
        final var token = tokens.issue(42L, NotificationType.WEEKLY_SUMMARY_EMAIL);
        final var signature = token.substring(token.indexOf('.') + 1);

        assertThat(tokens.read(encoded("v1.43.WEEKLY_SUMMARY_EMAIL") + "." + signature)).isEmpty();
        assertThat(tokens.read(encoded("v1.42.DEAD_LINK_EMAIL") + "." + signature)).isEmpty();
        assertThat(tokens.read(encoded("v1.42.WEEKLY_SUMMARY_EMAIL") + "." + signature)).isPresent();
    }

    @Test
    void anotherKeyCannotSign() {
        final var otherTokens = new UnsubscribeTokens(NotificationTestKeys.keys("b3RoZXItbm90aWZpY2F0aW9ucy1rZXktb2YtMzItYnl0ZXMhIQ=="));

        assertThat(tokens.read(otherTokens.issue(42L, NotificationType.WEEKLY_SUMMARY_EMAIL))).isEmpty();
    }

    @Test
    void garbageReadsEmpty() {
        final var valid = tokens.issue(42L, NotificationType.WEEKLY_SUMMARY_EMAIL);

        assertThat(tokens.read(null)).isEmpty();
        assertThat(tokens.read("")).isEmpty();
        assertThat(tokens.read("x.y")).isEmpty();
        assertThat(tokens.read("a".repeat(2_000))).isEmpty();
        assertThat(tokens.read(valid + "a".repeat(2_000))).isEmpty();
        assertThat(tokens.read("not base64!.@@@")).isEmpty();
        assertThat(tokens.read(valid + ".extra")).isEmpty();
        assertThat(tokens.read(valid.replace('.', '-'))).isEmpty();
    }

    @Test
    void aSignedPayloadOfTheWrongShapeReadsEmpty() {
        final var keys = NotificationTestKeys.keys();
        for (final var payload : new String[]{"v2.42.WEEKLY_SUMMARY_EMAIL", "v1.x.WEEKLY_SUMMARY_EMAIL", "v1.42.LINK_DEAD", "v1.42.FOO", "v1.42"}) {
            assertThat(tokens.read(encoded(payload) + "." + UnsubscribeTokens.signature(keys.unsubscribeKey(), payload))).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class, names = {"LINK_DEAD", "LINK_BACK", "DAILY_SUMMARY", "WEEKLY_SUMMARY"})
    void channelEventsCannotBeIssued(final NotificationType type) {
        assertThatThrownBy(() -> tokens.issue(42L, type)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenIsUrlSafe() {
        assertThat(tokens.issue(Long.MAX_VALUE, NotificationType.EXPIRY_REMINDER_EMAIL)).matches("^[A-Za-z0-9_.-]+$");
        assertThat(tokens.issue(1L, NotificationType.DEAD_LINK_EMAIL)).matches("^[A-Za-z0-9_.-]+$");
    }

    private static String encoded(final String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

}
