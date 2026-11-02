package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.LinkDestinationChange;
import cc.hrva.urlshortener.model.enums.ChangeChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record LinkDestinationChangeResponse(
        @Schema(description = "Change id", example = "12") Long id,
        @Schema(description = "Where the link pointed before", example = "https://example.com/old") String oldLongUrl,
        @Schema(description = "Where the link points after the change", example = "https://example.com/new") String newLongUrl,
        @Schema(description = "Email of the account that made the change", example = "owner@example.com") String changedBy,
        @Schema(description = "How the change was made: WEB (signed in) or API_KEY", example = "WEB") ChangeChannel via,
        @Schema(description = "When the change was made", example = "2026-11-01T10:00:00Z") LocalDateTime changedAt,
        @Schema(description = "An admin saved it although the link checks would have blocked it or sent it to review", example = "false") boolean forced) {

    public static LinkDestinationChangeResponse from(final LinkDestinationChange change) {
        return new LinkDestinationChangeResponse(change.getId(), change.getOldLongUrl(), change.getNewLongUrl(), change.getChangedBy(), change.getVia(),
                change.getChangedAt(), change.isForced());
    }

}
