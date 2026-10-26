package cc.hrva.urlshortener.service.bio;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BioPageTextTest {

    private static final String RIGHT_TO_LEFT_OVERRIDE = codePoints(0x202E);
    private static final String BELL = codePoints(0x0007);
    private static final String ACUTE = codePoints(0x0301);
    private static final String DIALYTIKA_TONOS = codePoints(0x0344);
    private static final String CAMERA = codePoints(0x1F4F8);
    private static final String FAMILY_OF_THREE = codePoints(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467);
    private static final String FAMILY_OF_FOUR = codePoints(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467, 0x200D, 0x1F466);
    private static final String CROATIAN_FLAG = codePoints(0x1F1ED, 0x1F1F7);
    private static final String RED_HEART = codePoints(0x2764, 0xFE0F);
    private static final String SEVENTEEN_UNIT_FAMILY = codePoints(
            0x1F468, 0x1F3FB, 0x200D, 0x1F469, 0x1F3FB, 0x200D, 0x1F467, 0x1F3FB, 0x200D, 0x1F466);

    @Test
    void controlAndBidiCharactersAreRemoved() {
        assertThat(BioPageText.clean("Ana" + RIGHT_TO_LEFT_OVERRIDE + "null" + BELL)).isEqualTo("Ananull");
    }

    @ParameterizedTest
    @MethodSource("bidiControls")
    void everyBidiControlIsRemoved(final int codePoint) {
        assertThat(BioPageText.clean("Ana" + codePoints(codePoint) + "Horvat")).isEqualTo("AnaHorvat");
    }

    @Test
    void whitespaceCollapses() {
        assertThat(BioPageText.clean("  Ana \n\t Horvat ")).isEqualTo("Ana Horvat");
        assertThat(BioPageText.clean("Ana\nHorvat")).isEqualTo("Ana Horvat");
        assertThat(BioPageText.clean("Ana" + codePoints(0x2028) + "Horvat" + codePoints(0x00A0, 0x2029))).isEqualTo("Ana Horvat");
    }

    @Test
    void emojiSequencesSurvive() {
        final var text = "Hi " + FAMILY_OF_THREE + " " + RED_HEART;

        assertThat(BioPageText.clean(text)).isEqualTo(text);
    }

    @Test
    void combiningMarksAreCappedAtThree() {
        assertThat(BioPageText.clean("x" + ACUTE.repeat(40))).isEqualTo("x" + ACUTE.repeat(3));
        assertThat(BioPageText.clean("a" + ACUTE.repeat(40))).isEqualTo(codePoints(0x00E1) + ACUTE.repeat(3));
    }

    @Test
    void marksThatNormalizingSplitsAreCappedAfterwards() {
        assertThat(BioPageText.clean("x" + DIALYTIKA_TONOS.repeat(3)))
                .isEqualTo(codePoints(0x1E8D, 0x0301, 0x0308, 0x0301));
    }

    @Test
    void textIsNfc() {
        assertThat(BioPageText.clean("e" + ACUTE)).isEqualTo(codePoints(0x00E9));
    }

    @Test
    void htmlStaysAsTyped() {
        assertThat(BioPageText.clean("<script>alert(1)</script>")).isEqualTo("<script>alert(1)</script>");
        assertThat(BioPageText.clean("</title><img src=x onerror=alert(1)>")).isEqualTo("</title><img src=x onerror=alert(1)>");
        assertThat(BioPageText.clean("javascript:alert(1)")).isEqualTo("javascript:alert(1)");
    }

    @Test
    void nothingIsCutAtTheLimits() {
        final var name = "a".repeat(48) + CAMERA;
        final var bio = "b".repeat(158) + CAMERA;

        assertThat(BioPageText.clean(name)).isEqualTo(name).hasSize(50);
        assertThat(BioPageText.clean(bio)).isEqualTo(bio).hasSize(160);
    }

    @Test
    void nullAndInvisibleTextBecomeEmpty() {
        assertThat(BioPageText.clean(null)).isEmpty();
        assertThat(BioPageText.clean(" " + RIGHT_TO_LEFT_OVERRIDE + " ")).isEmpty();
    }

    @Test
    void oneEmoji() {
        assertThat(FAMILY_OF_FOUR).hasSize(11);
        assertThat(SEVENTEEN_UNIT_FAMILY).hasSize(17).matches("\\X");

        assertThat(BioPageText.isSingleEmoji(CAMERA)).isTrue();
        assertThat(BioPageText.isSingleEmoji(FAMILY_OF_FOUR)).isTrue();
        assertThat(BioPageText.isSingleEmoji(CROATIAN_FLAG)).isTrue();
        assertThat(BioPageText.isSingleEmoji(RED_HEART)).isTrue();

        assertThat(BioPageText.isSingleEmoji(CAMERA + CAMERA)).isFalse();
        assertThat(BioPageText.isSingleEmoji(SEVENTEEN_UNIT_FAMILY)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a", "1", "#", "ab", " "})
    void notOneEmoji(final String value) {
        assertThat(BioPageText.isSingleEmoji(value)).isFalse();
    }

    private static Stream<Integer> bidiControls() {
        return Stream.of(
                Stream.of(0x061C, 0x200E, 0x200F),
                IntStream.rangeClosed(0x202A, 0x202E).boxed(),
                IntStream.rangeClosed(0x2066, 0x2069).boxed())
                .flatMap(codePoints -> codePoints);
    }

    private static String codePoints(final int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

}
