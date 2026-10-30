package cc.hrva.urlshortener.service.campaign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UrlShapesTest {

    private static final List<String> BUILT_IN_GENERIC_PATHS = List.of("/index.php", "/index.html", "/index.htm", "/search", "/watch", "/article",
            "/post", "/p", "/s", "/share", "/view", "/item", "/product");

    @Test
    void theCampaignShape() {
        final var first = UrlShapes.of("https://ll.omaehhm.com/wqamhcsom/?r=plfN8D&t=1793210826").orElseThrow();
        final var second = UrlShapes.of("https://qdbplibnsx.fvvxctw.com/wqamhcsom?r=zB3vpw&t=1793185791").orElseThrow();

        assertThat(first.key()).isEqualTo(second.key()).matches("[0-9a-f]{64}");
        assertThat(first).isEqualTo(second).isEqualTo(new UrlShape("/wqamhcsom", List.of("r", "t")));
        assertThat(first.display()).isEqualTo("/wqamhcsom?r&t");
    }

    @Test
    void theKeyIsTheSha256OfThePathAndTheNames() throws NoSuchAlgorithmException {
        assertThat(new UrlShape("/wqamhcsom", List.of("r", "t")).key()).isEqualTo(sha256("/wqamhcsom\nr,t"));
        assertThat(new UrlShape("/wqamhcsom", List.of()).key()).isEqualTo(sha256("/wqamhcsom\n"));
    }

    @Test
    void caseAndTrailingSlashesFold() {
        final var shape = UrlShapes.of("https://a.com/WQAMHCSOM///?T=1&R=2").orElseThrow();

        assertThat(shape).isEqualTo(new UrlShape("/wqamhcsom", List.of("r", "t")));
        assertThat(shape.key()).isEqualTo(UrlShapes.of("https://b.com/wqamhcsom?r=9&t=8").orElseThrow().key());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://a.com/?r=1", "https://a.com?r=1", "https://a.com/"})
    void rootWithOneNameIsGeneric(final String longUrl) {
        assertThat(UrlShapes.of(longUrl)).isEmpty();
    }

    @Test
    void emptyPathIsGeneric() {
        assertThat(UrlShapes.of("https://a.com")).isEmpty();
    }

    @Test
    void rootWithTwoNamesIsAShape() {
        final var shape = UrlShapes.of("https://a.com/?r=1&t=2").orElseThrow();

        assertThat(shape).isEqualTo(new UrlShape("/", List.of("r", "t")));
        assertThat(shape.display()).isEqualTo("/?r&t");
    }

    @Test
    void utmNamesAreLeftOutSoATaggedHomepageIsGeneric() {
        assertThat(UrlShapes.of("https://a.com/?utm_source=x&utm_medium=y&UTM_Campaign=z")).isEmpty();
        assertThat(UrlShapes.of("https://a.com/wqamhcsom?r=1&utm_source=x").orElseThrow())
                .isEqualTo(new UrlShape("/wqamhcsom", List.of("r")));
    }

    @ParameterizedTest
    @MethodSource("genericPathVariants")
    void builtInGenericPathsHaveNoShape(final String longUrl) {
        assertThat(UrlShapes.of(longUrl)).isEmpty();
    }

    @Test
    void theBuiltInGenericPathsAreTheThirteenOfTheContract() {
        assertThat(UrlShapes.GENERIC_PATHS).containsExactlyInAnyOrderElementsOf(BUILT_IN_GENERIC_PATHS);
    }

    @Test
    void aNonGenericPathWithOneNameIsAShape() {
        final var shape = UrlShapes.of("https://a.com/wqamhcsom?r=1").orElseThrow();

        assertThat(shape).isEqualTo(new UrlShape("/wqamhcsom", List.of("r")));
        assertThat(shape.display()).isEqualTo("/wqamhcsom?r");
    }

    @Test
    void aPathWithoutNamesIsAShapeWithoutAQuestionMark() {
        assertThat(UrlShapes.of("https://a.com/wqamhcsom").orElseThrow().display()).isEqualTo("/wqamhcsom");
    }

    @Test
    void repeatedNamesCountOnce() {
        assertThat(UrlShapes.of("https://a.com/wqamhcsom?t=1&r=2&R=3&t&r=").orElseThrow())
                .isEqualTo(new UrlShape("/wqamhcsom", List.of("r", "t")));
    }

    @Test
    void blankNamesAreLeftOut() {
        assertThat(UrlShapes.of("https://a.com/wqamhcsom?&=1&r=2&&").orElseThrow())
                .isEqualTo(new UrlShape("/wqamhcsom", List.of("r")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "http://exa mple.com/a b", "https://a.com/%zz?r=1&t=2", "mailto:someone@example.com", "not a url"})
    void anUnparseableUrlHasNoShape(final String longUrl) {
        assertThat(UrlShapes.of(longUrl)).isEmpty();
    }

    private static Stream<String> genericPathVariants() {
        return BUILT_IN_GENERIC_PATHS.stream().flatMap(path -> Stream.of(
                "https://a.com" + path + "?id=1",
                "https://a.com" + path,
                "https://a.com" + path + "/",
                "https://a.com" + path.toUpperCase(Locale.ROOT) + "/?id=1&ref=2"));
    }

    private static String sha256(final String text) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

}
