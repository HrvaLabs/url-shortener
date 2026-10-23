package cc.hrva.urlshortener.service.analytics;

import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public final class CountryCodes {

    private static final Pattern ALPHA_2 = Pattern.compile("[A-Za-z]{2}");
    private static final String NO_COUNTRY = "XX";
    private static final String TOR_EXIT = "T1";
    private static final String UNKNOWN = "unknown";
    private static final String TOR = "tor";

    private CountryCodes() {}

    public static String normalize(final String header) {
        final var code = StringUtils.strip(header);
        if (TOR_EXIT.equalsIgnoreCase(code)) {
            return TOR;
        }
        if (code == null || !ALPHA_2.matcher(code).matches() || NO_COUNTRY.equalsIgnoreCase(code)) {
            return UNKNOWN;
        }

        return code.toUpperCase(Locale.ROOT);
    }

}
