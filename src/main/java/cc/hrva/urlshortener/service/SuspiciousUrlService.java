package cc.hrva.urlshortener.service;

import java.util.List;
import java.util.Optional;

public interface SuspiciousUrlService {

    String IMITATES_PREFIX = "imitates ";
    String LOGIN_LURE = "login or verification lure";
    String SHORTENER_REASON = "goes through another shortener";
    String RISKY_ENDING_PREFIX = "high-risk domain ending .";

    List<String> findReasons(String longUrl);
    boolean isShortener(String longUrl);
    List<String> findTextReasons(String text);
    Optional<String> findImitatedBrand(String text);
    Optional<String> findBrandInHandle(String handle);
    Optional<String> findNamedBrand(String text);

    static boolean isSupporting(final String reason) {
        return SHORTENER_REASON.equals(reason) || reason.startsWith(RISKY_ENDING_PREFIX);
    }

    static List<String> shown(final List<String> reasons) {
        return reasons.stream().allMatch(SuspiciousUrlService::isSupporting) ? List.of() : reasons;
    }

}
