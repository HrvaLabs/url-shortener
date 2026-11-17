package cc.hrva.urlshortener.mcp;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record McpEraDecision(McpEra era, String correctedProtocolVersion) {

    private static final Set<String> LEGACY_VERSIONS = Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
    private static final String VERSION_META_KEY = "io.modelcontextprotocol/protocolVersion";

    private static final McpEraDecision LEGACY = new McpEraDecision(McpEra.LEGACY, null);
    private static final McpEraDecision MODERN = new McpEraDecision(McpEra.MODERN, null);

    public static McpEraDecision decide(
            final String httpMethod, final String protocolVersionHeader, final byte[] body, final JsonMapper jsonMapper) {
        if (!"POST".equalsIgnoreCase(httpMethod) || body == null || body.length == 0) {
            return LEGACY;
        }
        final var claim = readObject(body, jsonMapper);
        if (claim == null) {
            return LEGACY;
        }
        final var claimedVersion = claim.path("params").path("_meta").path(VERSION_META_KEY);
        final var claimed = claimedVersion.isString() && !claimedVersion.asString().isBlank();
        if (!claimed && "initialize".equals(claim.path("method").asString(null))) {
            return LEGACY;
        }
        if (claimed) {
            final var version = claimedVersion.asString();
            if (protocolVersionHeader != null && LEGACY_VERSIONS.contains(protocolVersionHeader.trim())) {
                return new McpEraDecision(McpEra.MODERN, version);
            }
            return MODERN;
        }
        if (protocolVersionHeader != null
                && !protocolVersionHeader.isBlank()
                && !LEGACY_VERSIONS.contains(protocolVersionHeader.trim())) {
            return MODERN;
        }
        return LEGACY;
    }

    private static JsonNode readObject(final byte[] body, final JsonMapper jsonMapper) {
        try {
            final var node = jsonMapper.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (final RuntimeException e) {
            return null;
        }
    }
}
