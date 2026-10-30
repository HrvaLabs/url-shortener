package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainProblem;

public record DomainInCloudflare(long id, String hostname, CustomDomainStatus status, DomainProblem problem, String cloudflareId,
                                 boolean deletePending) {
}
