package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record BlocklistStatus(
        long id,
        String name,
        @Schema(description = "How often the list is refreshed, in hours")
        int refreshIntervalHours,
        @Schema(description = "The last refresh that loaded the list or found it unchanged; null if it never did")
        LocalDateTime lastRefreshedAt,
        @Schema(description = "Refreshes that failed in a row since the last one that worked")
        long consecutiveErrors,
        @Schema(description = "True when the list missed two refresh periods in a row")
        boolean stale) {
}
