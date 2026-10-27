package cc.hrva.urlshortener.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    @Test
    void shouldRoundTripBase32() {
        final var original = "Hello, TOTP!".getBytes(StandardCharsets.UTF_8);
        final var encoded = Totp.base32Encode(original);
        assertThat(Totp.base32Decode(encoded)).isEqualTo(original);
    }

    @Test
    void aSecretTypedInLowerCaseDecodesTheSameUnderATurkishServerLocale() {
        final var secret = "JBSWY3DPEHPK3PXPIIZQ";
        final var expected = Totp.base32Decode(secret);
        final var serverLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(Totp.base32Decode(secret.toLowerCase(Locale.ROOT))).isEqualTo(expected);
        } finally {
            Locale.setDefault(serverLocale);
        }
    }

    @Test
    void shouldGenerateDecodableSecret() {
        final var secret = Totp.generateSecret();
        assertThat(secret).isNotBlank();
        assertThat(Totp.base32Decode(secret)).hasSize(20);
    }

    @Test
    void shouldVerifyCurrentCode() {
        final var secret = Totp.generateSecret();
        assertThat(Totp.verify(secret, Totp.currentCode(secret))).isTrue();
    }

    @Test
    void shouldRejectWrongCode() {
        final var secret = Totp.generateSecret();
        final var current = Totp.currentCode(secret);
        final var wrong = current.equals("000000") ? "111111" : "000000";
        assertThat(Totp.verify(secret, wrong)).isFalse();
    }

    @Test
    void shouldRejectMalformedCode() {
        final var secret = Totp.generateSecret();
        assertThat(Totp.verify(secret, "abc")).isFalse();
        assertThat(Totp.verify(secret, "12345")).isFalse();
        assertThat(Totp.verify(secret, null)).isFalse();
    }

    @Test
    void shouldBuildOtpAuthUri() {
        final var uri = Totp.buildOtpAuthUri("ABC234", "user@example.com", "hrva.cc");
        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=ABC234");
        assertThat(uri).contains("issuer=hrva.cc");
    }
}
