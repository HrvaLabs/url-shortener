package cc.hrva.urlshortener.repository.specification;

import java.util.Locale;
import java.util.regex.Pattern;

public final class LikePatterns {

    public static final char ESCAPE = '\\';

    private static final Pattern SPECIAL = Pattern.compile("[\\\\%_]");

    private LikePatterns() {}

    public static String containing(final String keyword) {
        return "%" + escaped(keyword) + "%";
    }

    static String startingWith(final String keyword) {
        return escaped(keyword) + "%";
    }

    private static String escaped(final String keyword) {
        return SPECIAL.matcher(keyword.toLowerCase(Locale.ROOT)).replaceAll("\\\\$0");
    }

}
