package cc.hrva.urlshortener.mcp.apps;

import java.time.Instant;

public record ConnectedAppDto(String clientId, String clientName, Instant firstConnected, Instant lastUsed) {
}
