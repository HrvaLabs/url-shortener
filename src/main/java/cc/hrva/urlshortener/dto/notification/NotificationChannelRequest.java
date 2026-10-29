package cc.hrva.urlshortener.dto.notification;

import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.validator.UrlValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationChannelRequest {

    @NotBlank(message = "Give the channel a name")
    @Size(max = 60, message = "Name must be 60 characters or fewer")
    @Schema(description = "A name to tell channels apart, 1 to 60 characters", example = "Team alerts")
    private String name;

    @NotNull(message = "Choose a channel type")
    @Schema(description = "Where the messages go; can't be changed after the channel is added", example = "DISCORD")
    private ChannelType type;

    @Schema(description = "Whether the channel gets messages; on when left out on create, unchanged when left out on edit", example = "true")
    private Boolean enabled;

    @Schema(description = "The events this channel gets: LINK_DEAD, LINK_BACK, DAILY_SUMMARY, WEEKLY_SUMMARY", example = "[\"LINK_DEAD\", \"WEEKLY_SUMMARY\"]")
    private Set<String> events;

    @ToString.Exclude
    @Size(max = UrlValidator.MAX_LONG_URL_LENGTH, message = "URL must be 2048 characters or fewer")
    @Schema(description = "The webhook or topic URL (DISCORD, SLACK, NTFY, WEBHOOK); on edit, leave empty to keep the saved one",
            example = "https://discord.com/api/webhooks/123/abc")
    private String target;

    @ToString.Exclude
    @Size(max = 255, message = "Bot token must be 255 characters or fewer")
    @Schema(description = "The bot token from @BotFather (TELEGRAM); on edit, leave empty to keep the saved one")
    private String botToken;

    @ToString.Exclude
    @Size(max = 40, message = "Chat ID must be 40 characters or fewer")
    @Schema(description = "The chat ID or @channel name (TELEGRAM); on edit, leave empty to keep the saved one", example = "-1001234567890")
    private String chatId;

    @ToString.Exclude
    @Size(max = 255, message = "Access token must be 255 characters or fewer")
    @Schema(description = "An optional ntfy access token, sent as a Bearer token (NTFY); on edit, leave empty to keep the saved one")
    private String accessToken;

}
