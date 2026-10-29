package cc.hrva.urlshortener.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;

public record SigningSecretResponse(
        @Schema(description = "The webhook's new signing secret; shown only this once") String signingSecret) {
}
