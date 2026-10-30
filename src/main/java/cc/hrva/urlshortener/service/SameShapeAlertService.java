package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.LinkVerdict;

public interface SameShapeAlertService {

    void anonymousLinkCreated(long urlId, String longUrl, LinkVerdict.Outcome outcome);

}
