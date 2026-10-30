package cc.hrva.urlshortener.model.enums;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SignInMethodTest {

    @ParameterizedTest
    @CsvSource({"google, GOOGLE", "Google, GOOGLE", "github, GITHUB", "GitHub, GITHUB"})
    void aKnownProviderIsItsMethod(final String provider, final SignInMethod method) {
        assertThat(SignInMethod.ofProvider(provider)).contains(method);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "facebook", "local", "password"})
    void anUnrecognisedProviderIsNoMethod(final String provider) {
        assertThat(SignInMethod.ofProvider(provider)).isEmpty();
    }

}
