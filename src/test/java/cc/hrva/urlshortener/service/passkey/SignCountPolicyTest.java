package cc.hrva.urlshortener.service.passkey;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SignCountPolicyTest {

    @ParameterizedTest(name = "stored {0}, presented {1} -> regression {2}")
    @CsvSource({
            "0, 0, false",
            "0, 5, false",
            "5, 6, false",
            "5, 5, true",
            "5, 3, true",
            "5, 0, false"
    })
    void aNonZeroCountThatDoesNotIncreaseIsARegression(final long stored, final long presented, final boolean regression) {
        assertThat(SignCountPolicy.isRegression(stored, presented)).isEqualTo(regression);
    }

}
