package cc.hrva.urlshortener.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "How the channel's last delivery or test went")
public record LastDelivery(
        @Schema(description = "SENT or FAILED", example = "SENT") String status,
        @Schema(description = "When it was made (UTC)", example = "2026-10-27T02:14:09") LocalDateTime at,
        @Schema(description = "The channel's HTTP answer, or null when none arrived", example = "204") Integer httpStatus,
        @Schema(description = "What went wrong, in words, or null when it was sent") String error) {
}
