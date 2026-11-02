package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.RequirementCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record MissingRequirement(
        @Schema(description = "What is missing", example = "TWO_STEP_SIGN_IN") RequirementCode code,
        @Schema(description = "What is missing, in plain words", example = "Turn on two-step sign-in") String text,
        @Schema(description = "When this requirement is met by itself; null when it needs an action", example = "2026-12-01T10:00:00Z")
        LocalDateTime availableFrom) {

    public static MissingRequirement of(final RequirementCode code, final String text) {
        return new MissingRequirement(code, text, null);
    }

}
