package cc.hrva.urlshortener.dto.bio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BioPageReasonDto(
        @NotBlank(message = "Reason is required")
        @Size(max = 200, message = "Reason must be 200 characters or fewer")
        String reason) {
}
