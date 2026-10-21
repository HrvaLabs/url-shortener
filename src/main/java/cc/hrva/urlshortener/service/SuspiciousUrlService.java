package cc.hrva.urlshortener.service;

import java.util.List;

public interface SuspiciousUrlService {

    String IMITATES_PREFIX = "imitates ";
    String LOGIN_LURE = "login or verification lure";

    List<String> findReasons(String longUrl);
    boolean isShortener(String longUrl);

}
