package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.DestinationChangeOverride;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record DestinationChangeStanding(
        @Schema(description = "The admin's choice for this account: AUTOMATIC (good standing decides), ALWAYS or NEVER", example = "AUTOMATIC")
        DestinationChangeOverride override,
        @Schema(description = "Whether the account can change where its hrva.cc links point now, with the site switch and the override applied",
                example = "false") boolean allowed,
        @Schema(description = "Whether the account meets every good standing requirement", example = "false") boolean inGoodStanding,
        @Schema(description = "The good standing requirements the account doesn't meet, whatever the override") List<MissingRequirement> missing) {

}
