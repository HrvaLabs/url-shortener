package cc.hrva.urlshortener.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public final class ReviewReasons {

    public static final String VISITOR_REPORT = "reported by a visitor";
    public static final String SIGN_IN_FORM = "Sign-in form on a warning-page link";
    public static final String SIGN_IN_FORM_NEW_ACCOUNT = "Sign-in form on a link from a new account";
    public static final String SIGN_IN_FORM_ACCOUNT = "Sign-in form on a link from an account";
    public static final String SIGN_IN_CHECK_COULD_NOT_RUN = "Sign-in form check couldn't run on a link from a new account";
    private static final String VISITOR_REPORT_PREFIX = VISITOR_REPORT + ": ";
    private static final Set<String> SIGN_IN_FORM_REASONS = Set.of(SIGN_IN_FORM, SIGN_IN_FORM_NEW_ACCOUNT, SIGN_IN_FORM_ACCOUNT, SIGN_IN_CHECK_COULD_NOT_RUN);

    private ReviewReasons() {}

    public static List<String> withoutVisitorText(final String reasons) {
        return Objects.requireNonNullElse(reasons, "").lines()
                .map(String::strip)
                .filter(StringUtils::isNotEmpty)
                .map(ReviewReasons::categoryOnly)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .map(occurrences -> occurrences.getValue() == 1
                        ? occurrences.getKey()
                        : "%s (%d times)".formatted(occurrences.getKey(), occurrences.getValue()))
                .toList();
    }

    public static boolean isSignInForm(final String reason) {
        return SIGN_IN_FORM_REASONS.contains(reason);
    }

    private static String categoryOnly(final String reason) {
        if (!reason.startsWith(VISITOR_REPORT_PREFIX)) {
            return reason;
        }
        return VISITOR_REPORT_PREFIX + StringUtils.substringBefore(reason.substring(VISITOR_REPORT_PREFIX.length()), ":");
    }

}
