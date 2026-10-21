package cc.hrva.urlshortener.service;

public interface RedirectChainService {

    Result check(long urlId);
    void checkLater(long urlId);

    enum Result {
        CHECKED, SKIPPED, UNREACHABLE, SAFE_BROWSING_UNAVAILABLE
    }

}
