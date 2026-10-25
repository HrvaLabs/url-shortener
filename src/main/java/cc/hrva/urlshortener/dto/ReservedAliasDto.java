package cc.hrva.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReservedAliasDto(
        @NotBlank(message = "Alias is required")
        @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "Alias can only contain letters, numbers, hyphens and underscores")
        @Size(max = 32, message = "Alias must be 32 characters or fewer")
        String alias,
        @NotBlank(message = "Reason is required")
        @Size(max = 200, message = "Reason must be 200 characters or fewer")
        String reason) {
}
