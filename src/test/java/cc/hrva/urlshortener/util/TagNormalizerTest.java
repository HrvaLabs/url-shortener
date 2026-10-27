package cc.hrva.urlshortener.util;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TagNormalizerTest {

    @Test
    void shouldReturnEmptyForNull() {
        assertThat(TagNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void aCapitalIIsLowercasedTheSameWayUnderATurkishServerLocale() {
        final var serverLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(TagNormalizer.normalize(List.of(" ISTANBUL "))).containsExactly("istanbul");
        } finally {
            Locale.setDefault(serverLocale);
        }
    }

    @Test
    void shouldTrimLowercaseAndDropBlanks() {
        final var result = TagNormalizer.normalize(List.of("  Work ", "CAMPAIGN", "   ", ""));

        assertThat(result).containsExactly("work", "campaign");
    }

    @Test
    void shouldDeduplicate() {
        final var result = TagNormalizer.normalize(List.of("work", "Work", "WORK"));

        assertThat(result).containsExactly("work");
    }

    @Test
    void shouldCapAtTenTags() {
        final var many = IntStream.range(0, 20).mapToObj(i -> "tag" + i).toList();

        assertThat(TagNormalizer.normalize(many)).hasSize(10);
    }

    @Test
    void shouldTruncateLongTags() {
        final var longTag = "a".repeat(50);

        final var result = TagNormalizer.normalize(List.of(longTag));

        assertThat(result).allSatisfy(tag -> assertThat(tag).hasSize(30));
    }
}
