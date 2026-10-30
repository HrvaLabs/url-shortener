package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.DomainRuleType;
import java.util.List;

public record DomainBlockPreviewResponse(String domain, List<String> hosts, int selectedLinks, int activeLinks, int heldLinks, DomainRuleType rule) {
}
