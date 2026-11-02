package cc.hrva.urlshortener.service;

import java.util.List;

public interface ClickCheckService {

    boolean mayRedirect(long urlId, String longUrl);
    boolean anyBlocked(List<String> urls);
    void reload();

}
