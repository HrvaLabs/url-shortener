package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class UrlUpdateDto {

    @NotNull(message = "ID is required")
    @Schema(description = "Unique identifier of the URL to update", example = "1")
    private Long id;
    @Schema(description = "New destination URL. Admins only; null leaves it unchanged", example = "https://example.com/new-page")
    private String longUrl;
    @Schema(description = "Maximum number of visits allowed", example = "200")
    private Long visitLimit;
    @Schema(description = "Expiration date and time", example = "2026-12-31T23:59:59")
    private LocalDateTime expirationDate;
    @Schema(description = "Tags (replaces existing when provided; null leaves them unchanged)", example = "[\"work\"]")
    private Set<String> tags;
    @Schema(description = "Password: non-empty sets/replaces it, empty string clears it, null leaves it unchanged", example = "s3cret")
    private String password;

}
