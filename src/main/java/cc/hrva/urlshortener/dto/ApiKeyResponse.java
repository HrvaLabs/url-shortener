package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.ApiKey;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record ApiKeyResponse(
        @Schema(description = "Unique identifier", example = "1")
        Long id,
        @Schema(description = "The start of the key, to recognise it: hrva_ and 6 characters, or the first 6 characters of a key made before v2.21. "
                + "The full key is shown only once, when it is made", example = "hrva_a1b2c3")
        String keyPrefix,
        @Schema(description = "How many links the key has created in total", example = "42")
        Long apiCallsUsed,
        @Schema(description = "When the key last created a link; null until its first use", example = "2026-10-30T09:15:00")
        LocalDateTime lastUsedAt,
        @Schema(description = "Creation date and time", example = "2026-05-16T10:00:00")
        LocalDateTime createDate,
        @Schema(description = "Expiration date and time; null when the key never expires", example = "2026-12-31T23:59:59")
        LocalDateTime expirationDate,
        @Schema(description = "Whether the API key is active", example = "true")
        boolean active,
        @Schema(description = "Reason for the current state: ACTIVE, EXPIRED, REVOKED", example = "ACTIVE")
        String status,
        @Schema(description = "Email of the key owner", example = "user@example.com")
        String ownerEmail) {

    public static ApiKeyResponse from(final ApiKey apiKey) {
        return new ApiKeyResponse(
                apiKey.getId(),
                apiKey.getKeyPrefix(),
                apiKey.getApiCallsUsed(),
                apiKey.getLastUsedAt(),
                apiKey.getCreateDate(),
                apiKey.getExpirationDate(),
                apiKey.isActive(),
                resolveStatus(apiKey),
                apiKey.getOwner() != null ? apiKey.getOwner().getEmail() : null);
    }

    private static String resolveStatus(final ApiKey apiKey) {
        if (apiKey.isActive()) {
            return "ACTIVE";
        }
        if (apiKey.getExpirationDate() != null && apiKey.getExpirationDate().isBefore(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "REVOKED";
    }

}
