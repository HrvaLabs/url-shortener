package cc.hrva.urlshortener.dto.bio;

import cc.hrva.urlshortener.model.enums.BioPageReviewAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BioPageReviewResolveDto(
        @NotNull(message = "Action is required")
        BioPageReviewAction action,
        @Size(max = 200, message = "Reason must be 200 characters or fewer")
        String reason) {
}
