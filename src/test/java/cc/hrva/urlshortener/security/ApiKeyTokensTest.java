package cc.hrva.urlshortener.security;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyTokensTest {

    @Test
    void aNewKeyIsHrvaAndThirtyTwoRandomBytesInBase62() {
        final var token = ApiKeyTokens.generate();

        assertThat(token.key()).matches("hrva_[0-9A-Za-z]{43}");
    }

    @Test
    void aNewKeyIsStoredOnlyAsItsHashAndItsDisplayPrefix() {
        final var token = ApiKeyTokens.generate();

        assertThat(token.hash()).isEqualTo(ApiKeyTokens.hash(token.key())).matches("[0-9a-f]{64}");
        assertThat(token.prefix()).isEqualTo(token.key().substring(0, 11)).startsWith("hrva_").hasSize(11);
        assertThat(token.toString()).doesNotContain(token.key());
    }

    @Test
    void everyKeyIsDifferent() {
        final var keys = new HashSet<String>();
        for (var made = 0; made < 1000; made++) {
            keys.add(ApiKeyTokens.generate().key());
        }

        assertThat(keys).hasSize(1000);
    }

    @Test
    void theHashIsTheSha256OfTheKeyInLowercaseHex() {
        assertThat(ApiKeyTokens.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ApiKeyTokens.hash("0b7c4f2e-6a1d-4e8b-9f3a-2c5d7e9f1a3b")).hasSize(64);
    }

    @Test
    void theHashesOfTwoKeysAreComparedInConstantTime() {
        assertThat(ApiKeyTokens.sameHash(ApiKeyTokens.hash("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")).isTrue();
        assertThat(ApiKeyTokens.sameHash(ApiKeyTokens.hash("abc"), ApiKeyTokens.hash("abd"))).isFalse();
        assertThat(ApiKeyTokens.sameHash(null, ApiKeyTokens.hash("abd"))).isFalse();
    }

}
