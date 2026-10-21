package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ApiKeyUpdateDto {

    @NotNull(message = "API key ID is required")
    @Schema(description = "Unique identifier of the API key to update", example = "1")
    private Long id;
    @Schema(description = "Maximum number of API calls allowed; 0 means unlimited, null leaves it unchanged", example = "2000")
    private Long apiCallsLimit;
    @Schema(description = "Number of API calls used", example = "100")
    private Long apiCallsUsed;
    @Schema(description = "Expiration date and time", example = "2026-12-31T23:59:59")
    private LocalDateTime expirationDate;
    @Schema(description = "Whether the API key is active", example = "true")
    private Boolean active;

}