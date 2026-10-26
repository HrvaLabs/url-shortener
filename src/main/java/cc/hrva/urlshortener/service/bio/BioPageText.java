package cc.hrva.urlshortener.service.bio;

import java.text.Normalizer;
import java.util.regex.Pattern;

public final class BioPageText {

    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\p{Cc}&&[^\\s]]|[\\x{00AD}\\x{061C}\\x{180E}\\x{200B}\\x{200E}\\x{200F}\\x{202A}-\\x{202E}\\x{2060}-\\x{2064}\\x{2066}-\\x{2069}\\x{FEFF}]");
    private static final Pattern STACKED_MARKS = Pattern.compile("(\\p{Mn}(?:\\p{Cf}*\\p{Mn}){2})(?:\\p{Cf}*\\p{Mn})+");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern ONE_GRAPHEME = Pattern.compile("\\X");
    private static final int MAX_EMOJI_LENGTH = 16;
    private static final int LAST_ASCII = 0x7F;
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    private BioPageText() {}

    public static String clean(final String text) {
        if (text == null) {
            return "";
        }
        final var visible = INVISIBLE.matcher(text).replaceAll("");
        final var normalized = Normalizer.normalize(visible, Normalizer.Form.NFC);
        final var capped = STACKED_MARKS.matcher(normalized).replaceAll("$1");
        return WHITESPACE.matcher(capped).replaceAll(" ").strip();
    }

    public static boolean isSingleEmoji(final String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_EMOJI_LENGTH) {
            return false;
        }
        final var first = value.codePointAt(0);
        final var last = value.codePointBefore(value.length());
        return first > LAST_ASCII && Character.isEmoji(first) && last != ZERO_WIDTH_JOINER && ONE_GRAPHEME.matcher(value).matches();
    }

}
