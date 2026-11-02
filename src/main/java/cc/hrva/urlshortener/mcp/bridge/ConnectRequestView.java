package cc.hrva.urlshortener.mcp.bridge;

public record ConnectRequestView(String clientName, String redirectHost, boolean loopback, boolean lookalike, String appScheme, boolean unknownDestination) {
}
