package cc.hrva.urlshortener.service;

public interface ClickCheckService {

    boolean mayRedirect(long urlId, String longUrl);
    void reload();

}
