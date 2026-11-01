package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    @Schema(description = "Expiration date and time, in the future. Kept for older clients; expiresInDays or neverExpires is the usual way", example = "2026-12-31T23:59:59Z")
    private LocalDateTime expirationDate;
    @Min(value = 1, message = ApiKeyCreateDto.DAYS_RANGE)
    @Max(value = 3650, message = ApiKeyCreateDto.DAYS_RANGE)
    @Schema(description = "Days from now until the key expires, 1 to 3650", example = "90")
    private Integer expiresInDays;
    @Schema(description = "true removes the expiry; the key works until it is revoked", example = "false")
    private Boolean neverExpires;
    @Schema(description = "Whether the API key is active", example = "true")
    private Boolean active;

}
