package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

public record PasskeyOptionsResponse(
        @Schema(description = "Send it back with the browser's answer. Single use, valid for 5 minutes", example = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n")
        String ceremonyId,
        @Schema(description = "The WebAuthn options for navigator.credentials, in their JSON form")
        JsonNode options) {
}
