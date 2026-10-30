package cc.hrva.urlshortener.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public final class EmailAddresses {

    private static final String MASK = "***";
    private static final Pattern ADDRESS = Pattern.compile("[^\\s@<>()\\[\\],;:\"]+@[A-Za-z0-9.-]+");
    private static final int MAX_LENGTH = 254;
    private static final Pattern LOCAL_PART = Pattern.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$");

    private EmailAddresses() {}

    public static boolean isValid(final String address) {
        if (address == null || address.length() > MAX_LENGTH) {
            return false;
        }
        final var at = address.lastIndexOf('@');
        if (at <= 0) {
            return false;
        }
        final var domain = address.substring(at + 1);
        return LOCAL_PART.matcher(address.substring(0, at)).matches()
                && !domain.endsWith(".")
                && DomainNames.HOST_NAME.matcher(domain).matches()
                && !StringUtils.isNumeric(StringUtils.substringAfterLast(domain, "."));
    }

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
