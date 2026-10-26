package cc.hrva.urlshortener.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public final class EmailAddresses {

    private static final String MASK = "***";
    private static final Pattern ADDRESS = Pattern.compile("[^\\s@<>()\\[\\],;:\"]+@[A-Za-z0-9.-]+");

    private EmailAddresses() {}

    public static String masked(final String address) {
        final var at = StringUtils.lastIndexOf(address, '@');
        if (at < 1) {
            return MASK;
        }
        return address.substring(0, address.offsetByCodePoints(0, 1)) + MASK + address.substring(at);
    }

    public static String maskedIn(final String text) {
        if (text == null) {
            return null;
        }
        return ADDRESS.matcher(text).replaceAll(match -> Matcher.quoteReplacement(masked(match.group())));
    }

}
