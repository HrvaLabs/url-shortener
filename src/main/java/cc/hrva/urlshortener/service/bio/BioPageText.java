package cc.hrva.urlshortener.service.bio;

import java.text.Normalizer;
import java.util.regex.Pattern;

public final class BioPageText {

    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\p{Cc}&&[^\\s]]|[\\x{061C}\\x{200E}\\x{200F}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]");
    private static final Pattern STACKED_MARKS = Pattern.compile("(\\p{Mn}{3})\\p{Mn}+");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern ONE_GRAPHEME = Pattern.compile("\\X");
    private static final int MAX_EMOJI_LENGTH = 16;
    private static final int LAST_ASCII = 0x7F;

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
        return first > LAST_ASCII && Character.isEmoji(first) && ONE_GRAPHEME.matcher(value).matches();
    }

}
