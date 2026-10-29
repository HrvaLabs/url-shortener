package cc.hrva.urlshortener.service.notification;

import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretBoxTest {

    private static final String TARGET = "https://discord.com/api/webhooks/123456/abcDEF-secret";
    private static final String ANA = SecretBox.contextFor(7L);
    private static final String BOB = SecretBox.contextFor(8L);

    private final SecretBox secretBox = new SecretBox(NotificationTestKeys.keys());

    @Test
    void sealsAndOpens() {
        final var sealed = secretBox.seal(TARGET, ANA);

        assertThat(sealed).doesNotContain("discord", "secret");
        assertThat(secretBox.open(sealed, ANA)).isEqualTo(TARGET);
    }

    @Test
    void contextNamesTheUser() {
        assertThat(SecretBox.contextFor(42L)).isEqualTo("notification-channel:42");
    }

    @Test
    void twoSealsOfOneValueDiffer() {
        final var first = secretBox.seal(TARGET, ANA);
        final var second = secretBox.seal(TARGET, ANA);

        assertThat(first).isNotEqualTo(second);
        assertThat(secretBox.open(first, ANA)).isEqualTo(secretBox.open(second, ANA));
    }

    @Test
    void anotherUsersContextCannotOpen() {
        final var sealed = secretBox.seal(TARGET, ANA);

        assertThatThrownBy(() -> secretBox.open(sealed, BOB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("sealed value can't be opened")
                .hasNoCause()
                .message().doesNotContain(TARGET);
    }

    @Test
    void anotherKeyCannotOpen() {
        final var sealed = secretBox.seal(TARGET, ANA);
        final var otherBox = new SecretBox(NotificationTestKeys.keys("b3RoZXItbm90aWZpY2F0aW9ucy1rZXktb2YtMzItYnl0ZXMhIQ=="));

        assertThatThrownBy(() -> otherBox.open(sealed, ANA)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedValueCannotOpen() {
        final var sealed = secretBox.seal(TARGET, ANA);
        final var bytes = Base64.getDecoder().decode(sealed.substring("v1:".length()));
        bytes[bytes.length - 1] ^= 1;
        final var tampered = "v1:" + Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> secretBox.open(tampered, ANA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("sealed value can't be opened");
        for (final var broken : new String[]{"", "v1:", "v1:not base64!", "v2:" + sealed.substring(3), "v1:AAAA", null}) {
            assertThatThrownBy(() -> secretBox.open(broken, ANA))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("sealed value can't be opened");
        }
    }

    @Test
    void sealedValueStartsWithV1() {
        final var sealed = secretBox.seal(TARGET, ANA);

        assertThat(sealed).startsWith("v1:");
        assertThat(Base64.getDecoder().decode(sealed.substring(3))).hasSize(12 + TARGET.length() + 16);
    }

    @Test
    void unicodeRoundTrips() {
        final var plain = "kanal-" + new String(Character.toChars(0x1F514)) + "-" + "čćšđž";

        assertThat(secretBox.open(secretBox.seal(plain, ANA), ANA)).isEqualTo(plain);
    }

}
