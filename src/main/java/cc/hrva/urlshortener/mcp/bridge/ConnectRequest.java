package cc.hrva.urlshortener.mcp.bridge;

public record ConnectRequest(String clientId, String redirectUri, String state, String authorizationQuery) {
}
