package cc.hrva.urlshortener.service.notification.channel;

import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.ChatText;
import cc.hrva.urlshortener.service.notification.NotificationMessage;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class SlackChannelSender implements ChannelSender {

    private static final NotificationMessages.Format SLACK = new NotificationMessages.Format(
            ChatText::slackEscaped, ChatText::slackEscaped, ChatText::slackEscaped, hidden -> "and " + hidden + " more:");

    private final JsonMapper jsonMapper;

    @Override
    public ChannelType type() {
        return ChannelType.SLACK;
    }

    @Override
    public OutgoingRequest render(final ChannelTarget target, final String eventName, final String deliveryUid, final String json,
            final NotificationMessage message) {
        return new OutgoingRequest(target.target(), OutgoingRequest.JSON, Map.of(),
                jsonMapper.writeValueAsBytes(Map.of("text", NotificationMessages.fitted(message, SLACK))));
    }

}
