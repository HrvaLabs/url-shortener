package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.DestinationChangeOverride;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record DestinationChangeOverrideDto(
        @NotNull(message = "Choose Automatic, Always allowed or Never allowed")
        @Schema(description = "AUTOMATIC, ALWAYS or NEVER", example = "NEVER") DestinationChangeOverride override) {

}
