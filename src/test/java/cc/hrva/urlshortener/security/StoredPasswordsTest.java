package cc.hrva.urlshortener.security;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class StoredPasswordsTest {

    @Test
    void aBcryptHashIsAPassword() {
        assertThat(StoredPasswords.isSet(new BCryptPasswordEncoder().encode("correct horse battery"))).isTrue();
    }

    @Test
    void nothingElseIsAPassword() {
        assertThat(StoredPasswords.isSet(null)).isFalse();
        assertThat(StoredPasswords.isSet("")).isFalse();
        assertThat(StoredPasswords.isSet(UUID.randomUUID().toString())).isFalse();
        assertThat(StoredPasswords.isSet(SecureTokens.random())).isFalse();
        assertThat(StoredPasswords.isSet("secret-hash")).isFalse();
    }

    @Test
    void aRemovedPasswordIsNoPasswordAndNeverTheSameTwice() {
        final var removed = StoredPasswords.none();

        assertThat(StoredPasswords.isSet(removed)).isFalse();
        assertThat(removed).hasSizeGreaterThanOrEqualTo(8).isNotEqualTo(StoredPasswords.none());
        assertThat(new BCryptPasswordEncoder().matches(removed, removed)).isFalse();
    }

}
