package cc.hrva.urlshortener.dto.notification;

import cc.hrva.urlshortener.model.enums.NotificationType;

public record UnsubscribeResponse(NotificationType type, boolean alreadyOff) {
}
