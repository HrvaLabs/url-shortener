package cc.hrva.urlshortener.service;

import java.util.List;

public interface SuspiciousUrlService {

    List<String> findReasons(String longUrl);
    boolean isShortener(String longUrl);

}
