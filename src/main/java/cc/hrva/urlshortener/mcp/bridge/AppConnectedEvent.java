package cc.hrva.urlshortener.mcp.bridge;

import cc.hrva.urlshortener.model.User;
import java.time.LocalDateTime;

public record AppConnectedEvent(User user, ConnectRequestView destination, LocalDateTime connectedAt) {
}
