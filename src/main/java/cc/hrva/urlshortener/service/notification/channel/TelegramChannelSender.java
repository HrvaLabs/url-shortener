package cc.hrva.urlshortener.service.notification.channel;

import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.NotificationMessage;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class TelegramChannelSender implements ChannelSender {

    private static final String API = "https://api.telegram.org/bot%s/sendMessage";

    private final JsonMapper jsonMapper;

    @Override
    public ChannelType type() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public OutgoingRequest render(final ChannelTarget target, final String eventName, final String deliveryUid, final String json,
            final NotificationMessage message) {
        final var body = new LinkedHashMap<String, Object>();
        body.put("chat_id", target.chatId());
        body.put("text", NotificationMessages.fitted(message, NotificationMessages.Format.PLAIN));
        body.put("disable_web_page_preview", true);
        return new OutgoingRequest(API.formatted(target.target()), OutgoingRequest.JSON, Map.of(), jsonMapper.writeValueAsBytes(body));
    }

}
