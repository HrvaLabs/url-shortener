package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import java.util.Collection;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailConsentReader {

    private final NotificationConsentRepository notificationConsentRepository;

    public boolean hasActiveEmailConsent(final long userId, final NotificationType type) {
        return notificationConsentRepository.existsActiveEmailConsent(userId, type);
    }

    public Set<Long> consenters(final NotificationType type, final Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(notificationConsentRepository.findConsentingUserIds(type, userIds));
    }

    public Set<Long> allConsenters(final NotificationType type) {
        return Set.copyOf(notificationConsentRepository.findAllConsentingUserIds(type));
    }

}
