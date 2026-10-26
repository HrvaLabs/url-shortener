package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.model.User;
import java.time.LocalDateTime;

public record PasskeyAddedEvent(User user, String label, LocalDateTime addedAt) {
}
