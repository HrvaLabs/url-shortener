package cc.hrva.urlshortener.repository.specification;

import java.util.Locale;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LikePatternsTest {

    @Test
    void aSearchTermIsLowercasedAndItsWildcardsEscaped() {
        assertThat(LikePatterns.containing("A%B_C\\D")).isEqualTo("%a\\%b\\_c\\\\d%");
    }

    @Test
    void aCapitalIIsLowercasedTheSameWayUnderATurkishServerLocale() {
        final var serverLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(LikePatterns.containing("ADMIN@EXAMPLE.IO")).isEqualTo("%admin@example.io%");
        } finally {
            Locale.setDefault(serverLocale);
        }
    }

}
