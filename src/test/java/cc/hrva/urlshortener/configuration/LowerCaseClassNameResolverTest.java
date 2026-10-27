package cc.hrva.urlshortener.configuration;

import java.util.Locale;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LowerCaseClassNameResolverTest {

    @Test
    void aClassNameWithACapitalIIsLowercasedTheSameWayUnderATurkishServerLocale() {
        final var serverLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(new LowerCaseClassNameResolver().idFromValue(new IllegalStateException())).isEqualTo("illegalstateexception");
        } finally {
            Locale.setDefault(serverLocale);
        }
    }

}
