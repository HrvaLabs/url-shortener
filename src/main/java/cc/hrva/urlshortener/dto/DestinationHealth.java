package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.Url;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record DestinationHealth(
        @Schema(description = "When the nightly check marked the destination dead (UTC)", example = "2026-10-27T02:11:40")
        LocalDateTime deadSince,
        @Schema(description = "The last definite failure: 404, 410, nxdomain, refused or tls", example = "404")
        String lastStatus) {

    public static DestinationHealth of(final Url url) {
        return url.getDestinationDeadSince() == null ? null : new DestinationHealth(url.getDestinationDeadSince(), url.getDestinationLastStatus());
    }

}
