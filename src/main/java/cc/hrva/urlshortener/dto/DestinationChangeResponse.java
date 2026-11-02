package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record DestinationChangeResponse(
        @Schema(description = "Whether the caller can change where this link points now", example = "false") boolean allowed,
        @Schema(description = "What is missing, empty when allowed") List<MissingRequirement> missing,
        @Schema(description = "Destination changes left for this link in the current day; null for admins", example = "5") Integer changesLeft) {

}
