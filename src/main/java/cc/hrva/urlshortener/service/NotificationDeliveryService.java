package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.service.notification.ChannelContent;
import java.util.Collection;
import java.util.Map;

public interface NotificationDeliveryService {

    int enqueue(NotificationType event, String dedupeKey, Collection<NotificationChannel> channels, Map<Long, ChannelContent> contentByOwnerId);

    void drain();

    void drainInBackground();

    void deleteOld();

}
