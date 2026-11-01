package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.ApiKey;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record CreatedApiKeyResponse(
        @Schema(description = "Unique identifier", example = "1")
        Long id,
        @Schema(description = "The full API key. Shown only in this answer: hrva.cc stores its hash, so it can't be shown again",
                example = "hrva_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v")
        String key,
        @Schema(description = "The start of the key, as the key lists show it", example = "hrva_a1b2c3")
        String keyPrefix,
        @Schema(description = "How many links the key has created in total", example = "0")
        Long apiCallsUsed,
        @Schema(description = "When the key last created a link; null until its first use", example = "null")
        LocalDateTime lastUsedAt,
        @Schema(description = "Creation date and time", example = "2026-10-30T10:00:00")
        LocalDateTime createDate,
        @Schema(description = "Expiration date and time; null when the key never expires", example = "2027-01-28T10:00:00")
        LocalDateTime expirationDate,
        @Schema(description = "Whether the API key is active", example = "true")
        boolean active,
        @Schema(description = "Reason for the current state: ACTIVE, EXPIRED, REVOKED", example = "ACTIVE")
        String status,
        @Schema(description = "Email of the key owner", example = "user@example.com")
        String ownerEmail) {

    public static CreatedApiKeyResponse from(final ApiKey apiKey, final String key) {
        final var listed = ApiKeyResponse.from(apiKey);
        return new CreatedApiKeyResponse(listed.id(), key, listed.keyPrefix(), listed.apiCallsUsed(), listed.lastUsedAt(), listed.createDate(),
                listed.expirationDate(), listed.active(), listed.status(), listed.ownerEmail());
    }

    @Override
    public String toString() {
        return "CreatedApiKeyResponse[id=" + id + ", keyPrefix=" + keyPrefix + "]";
    }

}
