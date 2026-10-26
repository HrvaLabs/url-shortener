package cc.hrva.urlshortener.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecureTokensTest {

    @Test
    void aTokenIs43Base64UrlCharacters() {
        assertThat(SecureTokens.random()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void twoTokensDiffer() {
        assertThat(SecureTokens.random()).isNotEqualTo(SecureTokens.random());
    }

}
