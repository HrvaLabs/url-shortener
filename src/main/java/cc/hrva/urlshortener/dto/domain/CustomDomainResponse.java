package cc.hrva.urlshortener.dto.domain;

import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import java.time.LocalDateTime;

public record CustomDomainResponse(
        long id,
        String hostname,
        CustomDomainStatus status,
        String reason,
        String reviewNote,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt,
        LocalDateTime activatedAt,
        LocalDateTime lastCheckedAt,
        DnsRecord dnsRecord,
        DomainSteps steps,
        DomainProblem problem,
        String problemText,
        String disabledNote,
        long links) {
}
