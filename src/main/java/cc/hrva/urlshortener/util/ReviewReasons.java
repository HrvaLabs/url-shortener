package cc.hrva.urlshortener.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public final class ReviewReasons {

    public static final String VISITOR_REPORT = "reported by a visitor";
    private static final String VISITOR_REPORT_PREFIX = VISITOR_REPORT + ": ";

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

    private static String categoryOnly(final String reason) {
        if (!reason.startsWith(VISITOR_REPORT_PREFIX)) {
            return reason;
        }
        return VISITOR_REPORT_PREFIX + StringUtils.substringBefore(reason.substring(VISITOR_REPORT_PREFIX.length()), ":");
    }

}
