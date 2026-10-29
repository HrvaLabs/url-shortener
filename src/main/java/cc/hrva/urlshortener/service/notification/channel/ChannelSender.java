package cc.hrva.urlshortener.service.notification.channel;

import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.NotificationMessage;

public interface ChannelSender {

    ChannelType type();

    OutgoingRequest render(ChannelTarget target, String eventName, String deliveryUid, String json, NotificationMessage message);

}
