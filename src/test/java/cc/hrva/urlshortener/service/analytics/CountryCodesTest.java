package cc.hrva.urlshortener.service.analytics;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CountryCodesTest {

    @ParameterizedTest
    @CsvSource({"HR, HR", "hr, HR", "' de ', DE"})
    void countryCodeIsUpperCaseWithoutSpaces(final String header, final String country) {
        assertThat(CountryCodes.normalize(header)).isEqualTo(country);
    }

    @ParameterizedTest
    @ValueSource(strings = {"T1", "t1"})
    void torExitIsTor(final String header) {
        assertThat(CountryCodes.normalize(header)).isEqualTo("tor");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"XX", "EUR", "1A", "H"})
    void missingOrInvalidCountryIsUnknown(final String header) {
        assertThat(CountryCodes.normalize(header)).isEqualTo("unknown");
    }

}
