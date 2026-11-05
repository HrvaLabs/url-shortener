package cc.hrva.urlshortener.service;

import java.util.List;
import java.util.Optional;

public interface SuspiciousUrlService {

    String IMITATES_PREFIX = "imitates ";
    String LOGIN_LURE = "login or verification lure";
    String SHORTENER_REASON = "goes through another shortener";
    String RISKY_ENDING_PREFIX = "high-risk domain ending .";
    String REDIRECTS_TO = "redirects to ";
    String REDIRECT_SEPARATOR = ": ";

    List<String> findReasons(String longUrl);
    boolean isShortener(String longUrl);
    List<String> findTextReasons(String text);
    Optional<String> findImitatedBrand(String text);
    Optional<String> findBrandInHandle(String handle);
    Optional<String> findNamedBrand(String text);

    static boolean isSupporting(final String reason) {
        final var judged = carriedReasonOf(reason);
        return SHORTENER_REASON.equals(judged) || judged.startsWith(RISKY_ENDING_PREFIX);
    }

    static List<String> deciding(final List<String> reasons) {
        return reasons.stream().filter(reason -> !isSupporting(reason)).toList();
    }

    static List<String> shown(final List<String> reasons) {
        return deciding(reasons).isEmpty() ? List.of() : reasons;
    }

    static String redirectReason(final String host, final String reason) {
        return REDIRECTS_TO + host + REDIRECT_SEPARATOR + reason;
    }

    private static String carriedReasonOf(final String reason) {
        if (!reason.startsWith(REDIRECTS_TO)) {
            return reason;
        }
        final var separator = reason.indexOf(REDIRECT_SEPARATOR, REDIRECTS_TO.length());
        return separator < 0 ? reason : reason.substring(separator + REDIRECT_SEPARATOR.length());
    }

}
