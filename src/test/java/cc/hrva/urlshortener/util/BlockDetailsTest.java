package cc.hrva.urlshortener.util;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlockDetailsTest {

    private static final String SEPARATOR = ", ";

    @Test
    void flaggedUrlsThatFitAreListedOnceEach() {
        assertThat(BlockDetails.flaggedUrls(List.of("https://a.example/x", "https://b.example/y", "https://a.example/x"), 255))
                .isEqualTo("https://a.example/x, https://b.example/y");
        assertThat(BlockDetails.flaggedUrls("https://a.example/x, https://b.example/y", 255)).isEqualTo("https://a.example/x, https://b.example/y");
    }

    @Test
    void blankDetailsStayAsTheyAre() {
        assertThat(BlockDetails.flaggedUrls((String) null, 255)).isNull();
        assertThat(BlockDetails.flaggedUrls("", 255)).isEmpty();
        assertThat(BlockDetails.flaggedUrls(List.of(), 255)).isEmpty();
    }

    @Test
    void aListThatDoesNotFitKeepsAsManyWholeUrlsAsFitThenSaysHowManyMore() {
        final var urls = IntStream.range(0, 10).mapToObj(index -> "https://threat-" + index + ".example/" + "p".repeat(20)).toList();

        final var detail = BlockDetails.flaggedUrls(urls, 255);

        assertThat(detail).isEqualTo(String.join(SEPARATOR, urls.subList(0, 5)) + ", and 5 more");
        assertThat(detail.length()).isLessThanOrEqualTo(255);
        assertThat(BlockDetails.flaggedUrls(String.join(SEPARATOR, urls), 255)).isEqualTo(detail);
    }

    @Test
    void theNumberOfUrlsShownIsTheLargestThatFits() {
        final var urls = IntStream.range(0, 30).mapToObj(index -> "https://t" + index + ".example/" + "q".repeat(index)).toList();

        for (var max = 60; max <= 400; max++) {
            final var detail = BlockDetails.flaggedUrls(urls, max);

            assertThat(detail.length()).as("max %d", max).isLessThanOrEqualTo(max);
            assertThat(detail).as("max %d", max).doesNotContain("...", "…");
            final var shown = detail.startsWith("https://") ? detail.split(SEPARATOR).length - (detail.contains(", and ") ? 1 : 0) : 0;
            if (shown > 0 && shown < urls.size()) {
                final var longer = String.join(SEPARATOR, urls.subList(0, shown + 1)) + (shown + 1 < urls.size() ? ", and " + (urls.size() - shown - 1) + " more" : "");
                assertThat(longer.length()).as("max %d", max).isGreaterThan(max);
            }
        }
    }

    @Test
    void aUrlThatCannotFitIsNamedByItsHostInsteadOfBeingCut() {
        final var long1 = "https://phish.example/" + "a".repeat(300);
        final var long2 = "https://malware.example/" + "b".repeat(300);

        assertThat(BlockDetails.flaggedUrls(List.of(long1), 255)).isEqualTo("phish.example");
        assertThat(BlockDetails.flaggedUrls(List.of(long1, long2, long1), 255)).isEqualTo("phish.example, malware.example");
    }

    @Test
    void whenEvenTheHostsDoNotFitTheAddressesAreCounted() {
        final var urls = IntStream.range(0, 40)
                .mapToObj(index -> "https://" + index + "h".repeat(60) + "." + ("h".repeat(60) + ".").repeat(3) + "example/" + "c".repeat(300))
                .toList();

        assertThat(BlockDetails.flaggedUrls(urls, 255)).isEqualTo("40 flagged addresses").hasSizeLessThanOrEqualTo(255);
    }

}
