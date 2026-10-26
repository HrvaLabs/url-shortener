package cc.hrva.urlshortener.service.twostep;

import java.util.Locale;
import java.util.regex.Pattern;

public final class TwoStepCodes {

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final String RECOVERY_CODE_SEPARATOR = "-";
    private static final int RECOVERY_CODE_GROUP_LENGTH = 4;
    private static final int RECOVERY_CODE_LENGTH_WITHOUT_SEPARATOR = 2 * RECOVERY_CODE_GROUP_LENGTH;

    private TwoStepCodes() {
    }

    public static String normalized(final String code) {
        if (code == null) {
            return null;
        }

        final var compact = WHITESPACE.matcher(code).replaceAll("").toUpperCase(Locale.ROOT);
        if (compact.length() == RECOVERY_CODE_LENGTH_WITHOUT_SEPARATOR && !compact.contains(RECOVERY_CODE_SEPARATOR)) {
            return compact.substring(0, RECOVERY_CODE_GROUP_LENGTH) + RECOVERY_CODE_SEPARATOR + compact.substring(RECOVERY_CODE_GROUP_LENGTH);
        }

        return compact;
    }

}
