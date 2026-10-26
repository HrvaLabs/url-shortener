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
    private static final String ZERO_WIDTH_JOINER = codePoints(0x200D);
    private static final String ZERO_WIDTH_NON_JOINER = codePoints(0x200C);
    private static final String ZERO_WIDTH_SPACE = codePoints(0x200B);
    private static final String SOFT_HYPHEN = codePoints(0x00AD);
    private static final String WORD_JOINER = codePoints(0x2060);
    private static final String BYTE_ORDER_MARK = codePoints(0xFEFF);
    private static final String HANGUL_FILLER = codePoints(0x3164);
    private static final String ENCLOSING_CIRCLE = codePoints(0x20DD);

    @Test
    void controlAndBidiCharactersAreRemoved() {
        assertThat(BioPageText.clean("Ana" + RIGHT_TO_LEFT_OVERRIDE + "null" + BELL)).isEqualTo("Ananull");
    }

    @ParameterizedTest
    @MethodSource("bidiControls")
    void everyBidiControlIsRemoved(final int codePoint) {
        assertThat(BioPageText.clean("Ana" + codePoints(codePoint) + "Horvat")).isEqualTo("AnaHorvat");
    }

    @ParameterizedTest
    @ValueSource(ints = {0x00AD, 0x180E, 0x200B, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0xFEFF, 0x2065, 0x206A, 0x206F, 0x3164, 0x115F,
            0x1160, 0xFFA0, 0xFFF9, 0xFFFA, 0xFFFB, 0x1D173, 0x1BCA0, 0xE0001, 0x110BD})
    void everyInvisibleCharacterIsRemoved(final int codePoint) {
        assertThat(BioPageText.clean("Ana" + codePoints(codePoint) + "Horvat")).isEqualTo("AnaHorvat");
    }

    @Test
    void textOfOnlyZeroWidthSpacesBecomesEmpty() {
        assertThat(BioPageText.clean(ZERO_WIDTH_SPACE)).isEmpty();
        assertThat(BioPageText.clean(ZERO_WIDTH_SPACE.repeat(20))).isEmpty();
    }

    @Test
    void aZeroWidthSpaceInsideAWordIsRemoved() {
        assertThat(BioPageText.clean("Insta" + ZERO_WIDTH_SPACE + "gram")).isEqualTo("Instagram");
    }

    @Test
    void anInvisibleFormatCharacterInsideAWordIsRemoved() {
        assertThat(BioPageText.clean("Insta" + codePoints(0x206A) + "gram")).isEqualTo("Instagram");
        assertThat(BioPageText.clean("Insta" + HANGUL_FILLER + "gram")).isEqualTo("Instagram");
    }

    @Test
    void textOfOnlyFillersAndFormatCharactersBecomesEmpty() {
        assertThat(BioPageText.clean(codePoints(0x206A).repeat(5))).isEmpty();
        assertThat(BioPageText.clean(HANGUL_FILLER.repeat(5))).isEmpty();
        assertThat(BioPageText.clean(codePoints(0x115F, 0x1160, 0xFFA0, 0xFFF9))).isEmpty();
    }

    @Test
    void softHyphenWordJoinerAndByteOrderMarkAreRemoved() {
        assertThat(BioPageText.clean(BYTE_ORDER_MARK + "In" + SOFT_HYPHEN + "sta" + WORD_JOINER + "gram")).isEqualTo("Instagram");
    }

    @Test
    void marksInterleavedWithJoinersAreCappedAtFour() {
        assertThat(BioPageText.clean("x" + (ACUTE + ZERO_WIDTH_JOINER).repeat(24))).isEqualTo("x" + (ACUTE + ZERO_WIDTH_JOINER).repeat(4));
        assertThat(BioPageText.clean("x" + (ACUTE + ZERO_WIDTH_NON_JOINER).repeat(24)))
                .isEqualTo("x" + (ACUTE + ZERO_WIDTH_NON_JOINER).repeat(4));
        assertThat(BioPageText.clean("x" + (ACUTE + ZERO_WIDTH_JOINER + ZERO_WIDTH_NON_JOINER).repeat(24)))
                .isEqualTo("x" + (ACUTE + ZERO_WIDTH_JOINER + ZERO_WIDTH_NON_JOINER).repeat(4));
        assertThat(BioPageText.clean("a" + (ACUTE + ZERO_WIDTH_JOINER).repeat(24)).codePoints().filter(BioPageTextTest::isNonSpacingMark).count())
                .isEqualTo(4);
    }

    @Test
    void enclosingMarksCountTowardsTheCap() {
        final var cleaned = BioPageText.clean("a" + (ACUTE + ENCLOSING_CIRCLE).repeat(24));

        assertThat(cleaned).isEqualTo(codePoints(0x00E1) + ENCLOSING_CIRCLE + ACUTE + ENCLOSING_CIRCLE + ACUTE);
        assertThat(cleaned.codePoints().filter(BioPageTextTest::isMark).count()).isEqualTo(4);
        assertThat(BioPageText.clean("x" + ENCLOSING_CIRCLE.repeat(40))).isEqualTo("x" + ENCLOSING_CIRCLE.repeat(4));
    }

    @Test
    void aMyanmarSyllableWithSpacingMarksIsKept() {
        final var syllable = codePoints(0x1000, 0x103C, 0x103D, 0x103E, 0x1031, 0x1038);

        assertThat(BioPageText.clean(syllable)).isEqualTo(syllable);
    }

    @ParameterizedTest
    @MethodSource("textThatMustSurvive")
    void scriptsAndSequencesSurviveUnchanged(final String text) {
        assertThat(BioPageText.clean(text)).isEqualTo(text);
    }

    @Test
    void marksInterleavedWithZeroWidthSpacesAreCappedAtFour() {
        assertThat(BioPageText.clean("x" + (ACUTE + ZERO_WIDTH_SPACE).repeat(24))).isEqualTo("x" + ACUTE.repeat(4));
    }

    @ParameterizedTest
    @MethodSource("emojiSequences")
    void joinedEmojiSequencesSurviveUnchanged(final String emoji) {
        assertThat(BioPageText.clean(emoji)).isEqualTo(emoji);
        assertThat(BioPageText.clean("Hi " + emoji + " there")).isEqualTo("Hi " + emoji + " there");
    }

    @Test
    void aPersianWordWithAZeroWidthNonJoinerIsKept() {
        final var word = codePoints(0x0645, 0x06CC, 0x200C, 0x062E, 0x0648, 0x0627, 0x0647, 0x0645);

        assertThat(BioPageText.clean(word)).isEqualTo(word);
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
    void combiningMarksAreCappedAtFour() {
        assertThat(BioPageText.clean("x" + ACUTE.repeat(40))).isEqualTo("x" + ACUTE.repeat(4));
        assertThat(BioPageText.clean("x" + ACUTE.repeat(5))).isEqualTo("x" + ACUTE.repeat(4));
        assertThat(BioPageText.clean("x" + ACUTE.repeat(4))).isEqualTo("x" + ACUTE.repeat(4));
        assertThat(BioPageText.clean("a" + ACUTE.repeat(40))).isEqualTo(codePoints(0x00E1) + ACUTE.repeat(4));
    }

    @Test
    void hebrewWithCantillationKeepsAllFourMarks() {
        final var nfc = codePoints(0x05D1, 0x05B0, 0x05BC, 0x05BD, 0x0591, 0x05E8);

        assertThat(BioPageText.clean(nfc)).isEqualTo(nfc);
        assertThat(BioPageText.clean(codePoints(0x05D1, 0x05BC, 0x05B0, 0x05BD, 0x0591, 0x05E8))).isEqualTo(nfc);
    }

    @Test
    void aMyanmarSyllableWithFourMarksIsKept() {
        final var syllable = codePoints(0x1000, 0x103B, 0x103D, 0x103E, 0x1036, 0x1037);

        assertThat(BioPageText.clean(syllable)).isEqualTo(syllable);
    }

    @ParameterizedTest
    @MethodSource("keptInvisibleCharactersOnly")
    void textOfOnlyKeptInvisibleCharactersBecomesEmpty(final String text) {
        assertThat(BioPageText.clean(text)).isEmpty();
        assertThat(BioPageText.clean("  " + text + "  ")).isEmpty();
    }

    @Test
    void keptInvisibleCharactersBesideVisibleTextStay() {
        assertThat(BioPageText.clean("Ana" + ZERO_WIDTH_JOINER)).isEqualTo("Ana" + ZERO_WIDTH_JOINER);
        assertThat(BioPageText.clean(codePoints(0x2764, 0xFE0F))).isEqualTo(codePoints(0x2764, 0xFE0F));
        assertThat(BioPageText.clean("Ana" + codePoints(0x2800))).isEqualTo("Ana" + codePoints(0x2800));
        assertThat(BioPageText.clean(codePoints(0xFFFC) + " Ana")).isEqualTo(codePoints(0xFFFC) + " Ana");
    }

    @Test
    void marksThatNormalizingSplitsAreCappedAfterwards() {
        assertThat(BioPageText.clean("x" + DIALYTIKA_TONOS.repeat(3)))
                .isEqualTo(codePoints(0x1E8D, 0x0301, 0x0308, 0x0301, 0x0308));
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

    @Test
    void anEmojiThatStartsOrEndsWithAJoinerIsNotOne() {
        assertThat(CAMERA + ZERO_WIDTH_JOINER).matches("\\X");

        assertThat(BioPageText.isSingleEmoji(CAMERA + ZERO_WIDTH_JOINER)).isFalse();
        assertThat(BioPageText.isSingleEmoji(ZERO_WIDTH_JOINER + CAMERA)).isFalse();
        assertThat(BioPageText.isSingleEmoji(FAMILY_OF_THREE + ZERO_WIDTH_JOINER)).isFalse();
    }

    @Test
    void anEmojiThatStartsOrEndsWithANonJoinerIsNotOne() {
        assertThat(CAMERA + ZERO_WIDTH_NON_JOINER).matches("\\X");
        assertThat(BioPageText.clean(CAMERA + ZERO_WIDTH_NON_JOINER)).isEqualTo(CAMERA + ZERO_WIDTH_NON_JOINER);

        assertThat(BioPageText.isSingleEmoji(CAMERA + ZERO_WIDTH_NON_JOINER)).isFalse();
        assertThat(BioPageText.isSingleEmoji(ZERO_WIDTH_NON_JOINER + CAMERA)).isFalse();
        assertThat(BioPageText.isSingleEmoji(FAMILY_OF_THREE + ZERO_WIDTH_NON_JOINER)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("emojiSequences")
    void everyJoinedEmojiSequenceIsOneEmoji(final String emoji) {
        assertThat(BioPageText.isSingleEmoji(emoji)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a", "1", "#", "ab", " "})
    void notOneEmoji(final String value) {
        assertThat(BioPageText.isSingleEmoji(value)).isFalse();
    }

    private static Stream<String> emojiSequences() {
        return Stream.of(
                FAMILY_OF_FOUR,
                RED_HEART,
                codePoints(0x2764, 0xFE0F, 0x200D, 0x1F525),
                codePoints(0x1F441, 0xFE0F, 0x200D, 0x1F5E8, 0xFE0F),
                codePoints(0x1F3F3, 0xFE0F, 0x200D, 0x1F308),
                CROATIAN_FLAG,
                codePoints(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F),
                codePoints(0x1F9D1, 0x1F3FB, 0x200D, 0x1F91D, 0x200D, 0x1F9D1, 0x1F3FF),
                codePoints(0x1F469, 0x1F3FD, 0x200D, 0x1F4BB));
    }

    private static Stream<String> textThatMustSurvive() {
        return Stream.of(
                codePoints(0x0023, 0xFE0F, 0x20E3),
                codePoints(0x1F3F4, 0x200D, 0x2620, 0xFE0F),
                codePoints(0x1F44B, 0x1F3FD),
                codePoints(0x0F56, 0x0F7C, 0x0F51, 0x0020, 0x0F66, 0x0F92, 0x0FB2),
                codePoints(0x05E9, 0x05B8, 0x05C1, 0x05DC, 0x05D5, 0x05B9, 0x05DD),
                codePoints(0x0928, 0x092E, 0x0938, 0x094D, 0x0924, 0x0947),
                codePoints(0x0E2A, 0x0E27, 0x0E31, 0x0E2A, 0x0E14, 0x0E35, 0x0020, 0x0E19, 0x0E49, 0x0E33),
                codePoints(0x09AC, 0x09BE, 0x0982, 0x09B2, 0x09BE),
                codePoints(0x1000, 0x103C, 0x103D, 0x103E, 0x1031, 0x1038),
                "Vi" + codePoints(0x1EC7) + "t Nam");
    }

    private static Stream<String> keptInvisibleCharactersOnly() {
        return Stream.of(
                codePoints(0x200D),
                codePoints(0x200C),
                codePoints(0xFE0F),
                codePoints(0x034F),
                codePoints(0x0301),
                codePoints(0x20DD),
                codePoints(0xE0041, 0xE0042),
                codePoints(0x17B4),
                codePoints(0x180B),
                codePoints(0x0301).repeat(3),
                codePoints(0x200D, 0x0020, 0x200C, 0x0020, 0xFE0F),
                codePoints(0x200D, 0x0301, 0x20DD, 0xE007F),
                codePoints(0x2800),
                codePoints(0x2800, 0x2800, 0x2800),
                codePoints(0xFFFC),
                codePoints(0x2800, 0x0020, 0xFFFC, 0x200D));
    }

    private static boolean isMark(final int codePoint) {
        final var type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean isNonSpacingMark(final int codePoint) {
        return Character.getType(codePoint) == Character.NON_SPACING_MARK;
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
