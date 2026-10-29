package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.model.enums.NotificationType;

public record UnsubscribeTarget(long userId, NotificationType type) {
}
