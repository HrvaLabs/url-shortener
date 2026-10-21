package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import java.time.LocalDateTime;

public record DomainRuleResponse(Long id, String domain, DomainRuleType type, String reason, String createdBy, LocalDateTime createdAt) {

    public static DomainRuleResponse from(final DomainRule rule) {
        return new DomainRuleResponse(rule.getId(), rule.getDomain(), rule.getType(), rule.getReason(), rule.getCreatedBy(), rule.getCreatedAt());
    }

}
