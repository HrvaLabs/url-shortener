package cc.hrva.urlshortener.service.bio;

import java.text.Normalizer;
import java.util.regex.Pattern;

public final class BioPageText {

    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\p{Cc}&&[^\\s]]|[\\p{Cf}&&[^\\x{200C}\\x{200D}\\x{E0020}-\\x{E007F}]]|[\\x{115F}\\x{1160}\\x{3164}\\x{FFA0}\\x{2065}]");
    private static final Pattern STACKED_MARKS = Pattern.compile("([\\p{Mn}\\p{Me}](?:\\p{Cf}*[\\p{Mn}\\p{Me}]){3})(?:\\p{Cf}*[\\p{Mn}\\p{Me}])+");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern ONE_GRAPHEME = Pattern.compile("\\X");
    private static final int MAX_EMOJI_LENGTH = 16;
    private static final int LAST_ASCII = 0x7F;
    private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    private BioPageText() {}

    public static String clean(final String text) {
        if (text == null) {
            return "";
        }
        final var visible = INVISIBLE.matcher(text).replaceAll("");
        final var normalized = Normalizer.normalize(visible, Normalizer.Form.NFC);
        final var capped = STACKED_MARKS.matcher(normalized).replaceAll("$1");
        final var collapsed = WHITESPACE.matcher(capped).replaceAll(" ").strip();
        return collapsed.codePoints().anyMatch(BioPageText::isVisible) ? collapsed : "";
    }

    public static boolean isSingleEmoji(final String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_EMOJI_LENGTH) {
            return false;
        }
        final var first = value.codePointAt(0);
        final var last = value.codePointBefore(value.length());
        return first > LAST_ASCII && Character.isEmoji(first) && !isJoiner(first) && !isJoiner(last) && ONE_GRAPHEME.matcher(value).matches();
    }

    private static boolean isVisible(final int codePoint) {
        final var type = Character.getType(codePoint);
        return type != Character.FORMAT && type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK
                && type != Character.SPACE_SEPARATOR && !Character.isWhitespace(codePoint);
    }

    private static boolean isJoiner(final int codePoint) {
        return codePoint == ZERO_WIDTH_JOINER || codePoint == ZERO_WIDTH_NON_JOINER;
    }

}
