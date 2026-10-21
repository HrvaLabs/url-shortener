package cc.hrva.urlshortener.service;

public interface RedirectChainService {

    void check(long urlId);
    void checkLater(long urlId);

}
