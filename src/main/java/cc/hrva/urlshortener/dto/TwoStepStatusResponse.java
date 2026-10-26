package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TwoStepStatusResponse(
        @Schema(description = "Whether two-step verification is on", example = "true")
        boolean enabled,
        @Schema(description = "Whether an authenticator app is set up", example = "false")
        boolean totp,
        @Schema(description = "How many passkeys and security keys the account has", example = "2")
        long passkeys,
        @Schema(description = "How many recovery codes are still unused", example = "10")
        long recoveryCodesRemaining) {
}
