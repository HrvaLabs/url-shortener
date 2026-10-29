package cc.hrva.urlshortener.dto.notification;

import cc.hrva.urlshortener.model.enums.NotificationType;
import java.time.LocalDateTime;

public record NotificationConsentResponse(NotificationType type, boolean consented, LocalDateTime grantedAt, String description) {
}
