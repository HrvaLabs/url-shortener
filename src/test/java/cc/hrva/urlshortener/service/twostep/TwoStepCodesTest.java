package cc.hrva.urlshortener.service.twostep;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TwoStepCodesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "e3hj-hdfu     | E3HJ-HDFU",
            "\"E3HJ-HDFU \" | E3HJ-HDFU",
            "\" E3HJ-HDFU\" | E3HJ-HDFU",
            "E3HJHDFU      | E3HJ-HDFU",
            "\"E3HJ HDFU\"  | E3HJ-HDFU",
            "e3hjhdfu      | E3HJ-HDFU",
            "\"163 225\"    | 163225",
            "\"163225 \"    | 163225",
            "163225        | 163225",
            "e3hj-hdf      | E3HJ-HDF",
            "E3HJ--HDFU    | E3HJ--HDFU",
            "abc-defgh     | ABC-DEFGH"
    })
    void stripsWhitespaceUpperCasesAndAddsTheRecoveryCodeDash(final String typed, final String normalized) {
        assertThat(TwoStepCodes.normalized(typed)).isEqualTo(normalized);
    }

    @Test
    void stripsLineBreaksTabsAndNoBreakSpaces() {
        assertThat(TwoStepCodes.normalized("\tE3HJ HDFU\r\n")).isEqualTo("E3HJ-HDFU");
        assertThat(TwoStepCodes.normalized("163 225\n")).isEqualTo("163225");
    }

    @Test
    void leavesNothingAndNullAsTheyAre() {
        assertThat(TwoStepCodes.normalized("   ")).isEmpty();
        assertThat(TwoStepCodes.normalized(null)).isNull();
    }

}
