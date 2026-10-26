package cc.hrva.urlshortener.service;

import java.util.List;
import java.util.Optional;

public interface SuspiciousUrlService {

    String IMITATES_PREFIX = "imitates ";
    String LOGIN_LURE = "login or verification lure";

    List<String> findReasons(String longUrl);
    boolean isShortener(String longUrl);
    List<String> findTextReasons(String text);
    Optional<String> findImitatedBrand(String text);
    Optional<String> findNamedBrand(String text);

}
