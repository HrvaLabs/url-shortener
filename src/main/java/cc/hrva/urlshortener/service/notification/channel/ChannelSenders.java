package cc.hrva.urlshortener.service.notification.channel;

import cc.hrva.urlshortener.client.ChannelPoster;
import cc.hrva.urlshortener.client.PostResult;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.NotificationMessage;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChannelSenders {

    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String ERROR = "error";

    private final Map<ChannelType, ChannelSender> senders = new EnumMap<>(ChannelType.class);
    private final ChannelPoster channelPoster;

    public ChannelSenders(final List<ChannelSender> senders, final ChannelPoster channelPoster) {
        senders.forEach(sender -> this.senders.put(sender.type(), sender));
        this.channelPoster = channelPoster;
    }

    public PostResult send(final NotificationChannel channel, final ChannelTarget target, final String eventName, final String deliveryUid,
            final String json, final NotificationMessage message) {
        try {
            final var request = senders.get(channel.getType()).render(target, eventName, deliveryUid, json, message);
            final var result = channelPoster.post(request.url(), request.contentType(), request.headers(), request.body(), TIMEOUT);
            log.debug("Notification channel post channel id={} type={} status={} failure={}", channel.getId(), channel.getType(),
                    result.httpStatus(), result.failure());
            return result;
        } catch (final RuntimeException exception) {
            log.warn("Notification channel post failed channel id={} type={} exception={}", channel.getId(), channel.getType(),
                    exception.getClass().getName());
            return new PostResult(0, ERROR);
        }
    }

}
