package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ReviewAction;

public interface ModerationService {

    UrlResponse blockLink(long urlId, BlockReason reason, String detail, String performedBy, String via);
    int blockDomain(String domain, String reason, String performedBy, String via, Integer maxLinks);
    void allowDomain(String domain, String reason, String performedBy, String via);
    void removeDomainRule(String domain, String performedBy, String via);
    int blockUser(long userId, String performedBy, String via, Integer maxLinks);
    int reactivateUser(long userId, String performedBy, String via);
    void resolveReview(long urlId, ReviewAction action, String domain, String performedBy, String via);
    void deleteLink(long urlId, String performedBy, String via);

}
