package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.notification.NotificationConsentResponse;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface NotificationConsentService {

    List<NotificationConsentResponse> mine();

    NotificationConsentResponse set(String type, boolean consented);

    boolean hasEmailConsent(long userId, NotificationType type);

    Set<Long> emailConsenters(NotificationType type, Collection<Long> userIds);

    boolean revokeByUnsubscribe(long userId, NotificationType type);

    void grantChannelEvents(User owner, NotificationChannel channel, Set<NotificationType> events);

    void revokeChannelEvents(long channelId, Set<NotificationType> events, ConsentSource source);

    Set<NotificationType> channelEvents(long channelId);

}
