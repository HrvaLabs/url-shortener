package cc.hrva.urlshortener.dto.domain;

import java.time.LocalDateTime;

public record DomainOwnerSummary(
        long id,
        String email,
        boolean active,
        LocalDateTime createdAt,
        long links,
        long blockedLinks,
        long openReviews,
        boolean possibleScammer) {
}
