package cc.hrva.urlshortener.dto.bio;

import java.time.LocalDateTime;
import java.util.List;

public record BioPageReviewEntryResponse(
        Long reviewId,
        Long pageId,
        String handle,
        String displayName,
        Long ownerId,
        String ownerEmail,
        boolean hidden,
        List<String> reasons,
        LocalDateTime flaggedAt) {
}
