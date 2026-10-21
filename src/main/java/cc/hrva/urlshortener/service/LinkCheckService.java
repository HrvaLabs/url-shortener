package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.LinkVerdict;

public interface LinkCheckService {

    LinkVerdict check(String longUrl);
    LinkVerdict explain(String longUrl);

}
