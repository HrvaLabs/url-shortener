package cc.hrva.urlshortener.service.notification.channel;

import cc.hrva.urlshortener.client.DiscordMessage;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.alert.DiscordText;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.NotificationMessage;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class DiscordChannelSender implements ChannelSender {

    private static final NotificationMessages.Format DISCORD = new NotificationMessages.Format(
            title -> "**" + DiscordText.plain(title) + "**",
            DiscordText::plain,
            link -> "<" + link + ">",
            hidden -> "and " + hidden + " more:");

    private final JsonMapper jsonMapper;

    @Override
    public ChannelType type() {
        return ChannelType.DISCORD;
    }

    @Override
    public OutgoingRequest render(final ChannelTarget target, final String eventName, final String deliveryUid, final String json,
            final NotificationMessage message) {
        final var content = NotificationMessages.fitted(message, DISCORD);
        return new OutgoingRequest(target.target(), OutgoingRequest.JSON, Map.of(),
                jsonMapper.writeValueAsBytes(DiscordMessage.withoutMentionsOrEmbeds(content)));
    }

}
