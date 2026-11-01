package cc.hrva.urlshortener.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class BcryptPasswordsTest {

    private final BcryptPasswords passwords = new BcryptPasswords(new BCryptPasswordEncoder(4));

    @Test
    void aPasswordUpToSeventyTwoBytesMatchesItsHash() {
        final var password = "a".repeat(72);

        assertThat(passwords.matches(password, passwords.encode(password))).isTrue();
        assertThat(passwords.matches("wrong", passwords.encode(password))).isFalse();
    }

    @Test
    void aLongerPasswordNeverMatchesEvenWhenItsFirstSeventyTwoBytesDo() {
        final var hashOfTheFirst72 = new BCryptPasswordEncoder(4).encode("a".repeat(72));

        assertThat(passwords.matches("a".repeat(73), hashOfTheFirst72)).isFalse();
        assertThat(passwords.matches("\u010d".repeat(37), passwords.encode("\u010d".repeat(36)))).isFalse();
    }

    @Test
    void tooLongCountsBytesNotCharacters() {
        assertThat(BcryptPasswords.tooLong("\u010d".repeat(36))).isFalse();
        assertThat(BcryptPasswords.tooLong("\u010d".repeat(37))).isTrue();
    }

}
