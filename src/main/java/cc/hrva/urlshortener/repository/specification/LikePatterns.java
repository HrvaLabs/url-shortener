package cc.hrva.urlshortener.repository.specification;

import java.util.regex.Pattern;

final class LikePatterns {

    static final char ESCAPE = '\\';

    private static final Pattern SPECIAL = Pattern.compile("[\\\\%_]");

    private LikePatterns() {}

    static String containing(final String keyword) {
        return "%" + SPECIAL.matcher(keyword.toLowerCase()).replaceAll("\\\\$0") + "%";
    }

}
