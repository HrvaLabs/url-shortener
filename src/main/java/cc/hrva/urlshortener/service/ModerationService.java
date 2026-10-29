package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.DomainBlockPreviewResponse;
import cc.hrva.urlshortener.dto.DomainBulkBlockResponse;
import cc.hrva.urlshortener.dto.DomainRuleCreatedResponse;
import cc.hrva.urlshortener.dto.DomainRuleDto;
import cc.hrva.urlshortener.dto.DomainRuleResponse;
import cc.hrva.urlshortener.dto.ReviewBulkResolveResponse;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.model.enums.ReviewAction;
import java.util.List;

public interface ModerationService {

    UrlResponse blockLink(long urlId, BlockReason reason, String detail, String performedBy, String via);
    int blockDomain(String domain, String reason, String performedBy, String via, Integer maxLinks);
    List<DomainBlockPreviewResponse> previewDomainBlocks(List<Long> urlIds);
    DomainBulkBlockResponse blockDomains(List<String> domains, String reason, String performedBy, String via);
    int allowDomain(String domain, String reason, String performedBy, String via);
    DomainRuleCreatedResponse createDomainRule(DomainRuleDto domainRuleDto, String performedBy, String via);
    List<DomainRuleResponse> domainRules(DomainRuleType type);
    void removeDomainRule(String domain, String performedBy, String via);
    void removeDomainRule(long ruleId, String performedBy, String via);
    int blockUser(long userId, String performedBy, String via, Integer maxLinks);
    int reactivateUser(long userId, String performedBy, String via);
    void resolveReview(long urlId, ReviewAction action, String domain, String performedBy, String via);
    ReviewBulkResolveResponse resolveReviews(List<Long> urlIds, ReviewAction action, String performedBy, String via);
    void deleteLink(long urlId, String performedBy, String via);

}
