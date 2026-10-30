package cc.hrva.urlshortener.service.domain;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PunycodeTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "egbpdaj6bu4bxfgehfvwxn",
            "ihqwcrb4cv8a8dqg056pqjye",
            "ihqwctvzc91f659drss3x8bo0yb",
            "Proprostnemluvesky-uyb24dma41a",
            "4dbcagdahymbxekheh6e0a7fei0b",
            "i1baa7eci9glrd9b2ae1bj0hfcgg6iyaf8o0a1dig0cd",
            "n8jok5ay5dzabd5bym9f0cm5685rrjetr6pdxa",
            "989aomsvi5e83db1d2a355cv1e0vak1dwrv93d5xbh15a0dt30a5jpsd879ccm6fea98c",
            "PorqunopuedensimplementehablarenEspaol-fmd56a",
            "TisaohkhngthchnitingVit-kjcr8268qyxafd2f1b9g",
            "3B-ww4c5e180e575a65lsy2b",
            "-with-SUPER-MONKEYS-pc58ag80a8qai00g7n9n",
            "Hello-Another-Way--fc4qua05auwb3674vfr0b",
            "2-u9tlzr9756bt3uc0v",
            "MajiKoi5-783gue6qz075azm5e",
            "de-jg4avhby1noc0d",
            "d9juau41awczczp",
            "-> $1.00 <--"})
    void theRfc3492SamplesSurviveADecodeAndEncode(final String encoded) {
        final var decoded = Punycode.decode(encoded);

        assertThat(decoded).isPresent();
        assertThat(Punycode.encode(decoded.orElseThrow())).contains(encoded);
    }

    @Test
    void theRfc3492SamplesDecodeToTheirText() {
        assertThat(Punycode.decode("egbpdaj6bu4bxfgehfvwxn")).contains(text(0x644, 0x64A, 0x647, 0x645, 0x627, 0x628, 0x62A, 0x643, 0x644,
                0x645, 0x648, 0x634, 0x639, 0x631, 0x628, 0x64A, 0x61F));
        assertThat(Punycode.decode("ihqwcrb4cv8a8dqg056pqjye")).contains(text(0x4ED6, 0x4EEC, 0x4E3A, 0x4EC0, 0x4E48, 0x4E0D, 0x8BF4,
                0x4E2D, 0x6587));
        assertThat(Punycode.decode("3B-ww4c5e180e575a65lsy2b")).contains(text(0x33, 0x5E74, 0x42, 0x7D44, 0x91D1, 0x516B, 0x5148, 0x751F));
        assertThat(Punycode.decode("d9juau41awczczp")).contains(text(0x305D, 0x306E, 0x30B9, 0x30D4, 0x30FC, 0x30C9, 0x3067));
        assertThat(Punycode.decode("-> $1.00 <--")).contains("-> $1.00 <-");
    }

    @Test
    void anUppercaseDigitDecodesLikeItsLowercaseAndEncodesLowercase() {
        final var russian = text(0x43F, 0x43E, 0x447, 0x435, 0x43C, 0x443, 0x436, 0x435, 0x43E, 0x43D, 0x438, 0x43D, 0x435, 0x433, 0x43E,
                0x432, 0x43E, 0x440, 0x44F, 0x442, 0x43F, 0x43E, 0x440, 0x443, 0x441, 0x441, 0x43A, 0x438);

        assertThat(Punycode.decode("b1abfaaepdrnnbgefbaDotcwatmq2g4l")).contains(russian);
        assertThat(Punycode.encode(russian)).contains("b1abfaaepdrnnbgefbadotcwatmq2g4l");
    }

    @Test
    void labelsTheJdkNameprepWouldChangeDecodeAsTheyAre() {
        assertThat(Punycode.decode("zca")).contains(text(0xDF));
        assertThat(Punycode.decode("strae-oqa")).contains(text(0x73, 0x74, 0x72, 0x61, 0xDF, 0x65));
        assertThat(Punycode.decode("pxac2aodamd")).contains(text(0x3BF, 0x3B4, 0x3C5, 0x3C3, 0x3C3, 0x3B5, 0x3C5, 0x3C2));
        assertThat(Punycode.decode("mgba3gch5960b")).contains(text(0x646, 0x627, 0x645, 0x200C, 0x647, 0x627));
        assertThat(Punycode.encode(text(0x73, 0x74, 0x72, 0x61, 0xDF, 0x65))).contains("strae-oqa");
    }

    @Test
    void encodingKeepsAllBasicTextAndAddsTheDelimiter() {
        assertThat(Punycode.encode("abc")).contains("abc-");
        assertThat(Punycode.decode("abc-")).contains("abc");
        assertThat(Punycode.encode("")).contains("");
        assertThat(Punycode.decode("")).contains("");
    }

    @Test
    void aSupplementaryCodePointRoundTrips() {
        final var pileOfPoo = text(0x1F4A9);

        assertThat(Punycode.encode(pileOfPoo)).contains("ls8h");
        assertThat(Punycode.decode("ls8h")).contains(pileOfPoo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a9", "ab!", "ab-c!", "99999999999999"})
    void unreadableInputDecodesToNothing(final String encoded) {
        assertThat(Punycode.decode(encoded)).isEmpty();
    }

    @Test
    void zzzzzzIsNotTheEncodingOfAnything() {
        assertThat(Punycode.decode("zzzzzz").flatMap(Punycode::encode)).isNotEqualTo(Optional.of("zzzzzz"));
    }

    @Test
    void aNonBasicCharacterBeforeTheDelimiterDecodesToNothing() {
        assertThat(Punycode.decode(text(0xE9) + "-abc")).isEmpty();
    }

    private static String text(final int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

}
