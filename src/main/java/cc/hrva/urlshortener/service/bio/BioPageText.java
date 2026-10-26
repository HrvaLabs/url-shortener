package cc.hrva.urlshortener.service.bio;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.regex.Pattern;

public final class BioPageText {

    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\p{Cc}&&[^\\s]]|[\\p{Cf}&&[^\\x{200C}\\x{200D}\\x{E0020}-\\x{E007F}]]|[\\x{115F}\\x{1160}\\x{3164}\\x{FFA0}\\x{2065}]");
    private static final Pattern STACKED_MARKS = Pattern.compile("([\\p{Mn}\\p{Me}](?:\\p{Cf}*[\\p{Mn}\\p{Me}]){3})(?:\\p{Cf}*[\\p{Mn}\\p{Me}])+");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n?|\\n");
    private static final Pattern WHITESPACE_WITHIN_A_LINE = Pattern.compile("[\\s\\p{Z}&&[^\\n]]+");
    private static final Pattern ONE_GRAPHEME = Pattern.compile("\\X");
    private static final String NEWLINE = "\n";
    private static final String SPACE = " ";
    private static final int MAX_BIO_LINES = 5;
    private static final int MAX_EMOJI_LENGTH = 16;
    private static final int LAST_ASCII = 0x7F;
    private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int BRAILLE_PATTERN_BLANK = 0x2800;
    private static final int OBJECT_REPLACEMENT_CHARACTER = 0xFFFC;

    private BioPageText() {}

    public static String clean(final String text) {
        if (text == null) {
            return "";
        }
        final var collapsed = WHITESPACE.matcher(normalized(text)).replaceAll(SPACE).strip();
        return hasVisible(collapsed) ? collapsed : "";
    }

    public static String cleanBio(final String text) {
        if (text == null) {
            return "";
        }
        final var lines = Arrays.stream(LINE_BREAK.split(normalized(text), -1))
                .map(line -> WHITESPACE_WITHIN_A_LINE.matcher(line).replaceAll(SPACE).strip())
                .filter(BioPageText::hasVisible)
                .toList();
        if (lines.size() <= MAX_BIO_LINES) {
            return String.join(NEWLINE, lines);
        }
        return String.join(NEWLINE, lines.subList(0, MAX_BIO_LINES - 1)) + NEWLINE
                + String.join(SPACE, lines.subList(MAX_BIO_LINES - 1, lines.size()));
    }

    public static boolean isSingleEmoji(final String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_EMOJI_LENGTH) {
            return false;
        }
        final var first = value.codePointAt(0);
        final var last = value.codePointBefore(value.length());
        return first > LAST_ASCII && Character.isEmoji(first) && !isJoiner(first) && !isJoiner(last) && ONE_GRAPHEME.matcher(value).matches();
    }

    private static String normalized(final String text) {
        final var visible = INVISIBLE.matcher(text).replaceAll("");
        final var composed = Normalizer.normalize(visible, Normalizer.Form.NFC);
        return STACKED_MARKS.matcher(composed).replaceAll("$1");
    }

    private static boolean hasVisible(final String text) {
        return text.codePoints().anyMatch(BioPageText::isVisible);
    }

    private static boolean isVisible(final int codePoint) {
        final var type = Character.getType(codePoint);
        return type != Character.FORMAT && type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK
                && type != Character.SPACE_SEPARATOR && !Character.isWhitespace(codePoint)
                && codePoint != BRAILLE_PATTERN_BLANK && codePoint != OBJECT_REPLACEMENT_CHARACTER;
    }

    private static boolean isJoiner(final int codePoint) {
        return codePoint == ZERO_WIDTH_JOINER || codePoint == ZERO_WIDTH_NON_JOINER;
    }

}
