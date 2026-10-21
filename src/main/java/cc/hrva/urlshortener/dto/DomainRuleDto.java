package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.DomainRuleType;

public record DomainRuleDto(String domain, DomainRuleType type, String reason) {
}
