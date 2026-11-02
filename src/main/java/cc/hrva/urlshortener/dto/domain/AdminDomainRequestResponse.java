package cc.hrva.urlshortener.dto.domain;

import cc.hrva.urlshortener.model.enums.DomainRequestStatus;
import java.time.LocalDateTime;

public record AdminDomainRequestResponse(
        long id,
        DomainRequestStatus status,
        int requestedTotal,
        int limitAtRequest,
        int currentLimit,
        long used,
        String reason,
        String adminNote,
        String reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt,
        DomainOwnerSummary owner) {
}
