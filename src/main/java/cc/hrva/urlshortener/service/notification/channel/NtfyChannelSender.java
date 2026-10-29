package cc.hrva.urlshortener.service.notification.channel;

import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.NotificationMessage;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

@Component
public class NtfyChannelSender implements ChannelSender {

    private static final String PLAIN_TEXT = "text/plain; charset=utf-8";

    @Override
    public ChannelType type() {
        return ChannelType.NTFY;
    }

    @Override
    public OutgoingRequest render(final ChannelTarget target, final String eventName, final String deliveryUid, final String json,
            final NotificationMessage message) {
        final var headers = new LinkedHashMap<String, String>();
        headers.put("Title", "hrva.cc");
        if (message.link() != null) {
            headers.put("Click", message.link());
        }
        headers.put("Tags", "link");
        if (target.accessToken() != null) {
            headers.put("Authorization", "Bearer " + target.accessToken());
        }
        return new OutgoingRequest(target.target(), PLAIN_TEXT, headers,
                NotificationMessages.fitted(message, NotificationMessages.Format.PLAIN).getBytes(StandardCharsets.UTF_8));
    }

}
