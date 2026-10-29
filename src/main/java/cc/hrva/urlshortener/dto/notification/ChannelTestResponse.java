package cc.hrva.urlshortener.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;

public record ChannelTestResponse(
        @Schema(description = "Whether the channel accepted the test message") boolean delivered,
        @Schema(description = "The channel's HTTP answer, or null when none arrived", example = "404") Integer httpStatus,
        @Schema(description = "What went wrong, in words, or null when it was delivered",
                example = "The channel answered with HTTP 404. Check the URL.") String error) {
}
