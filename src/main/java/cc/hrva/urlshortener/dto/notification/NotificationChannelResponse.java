package cc.hrva.urlshortener.dto.notification;

import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.NotificationType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

public record NotificationChannelResponse(
        Long id,
        String name,
        ChannelType type,
        boolean enabled,
        @Schema(description = "The events this channel gets, each one a recorded consent") List<NotificationType> events,
        @Schema(description = "The scheme, host and last 4 characters of the URL, or the bot token's last 4") String maskedTarget,
        @Schema(description = "The Telegram chat ID, which isn't secret") String chatId,
        boolean hasAccessToken,
        @Schema(description = "When the channel turned itself off after failing on 10 different days") LocalDateTime autoDisabledAt,
        LastDelivery lastDelivery,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "The webhook's signing secret, only in the answer to adding a webhook channel") String signingSecret) {
}
