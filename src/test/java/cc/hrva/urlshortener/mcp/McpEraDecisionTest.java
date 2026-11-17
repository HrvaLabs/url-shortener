package cc.hrva.urlshortener.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class McpEraDecisionTest {

    private static final String CLAIM = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}";
    private static final String MODERN_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{" + CLAIM + "}}";
    private static final String PLAIN_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private McpEraDecision decide(final String method, final String header, final String body) {
        return McpEraDecision.decide(method, header, body.getBytes(StandardCharsets.UTF_8), jsonMapper);
    }

    @Test
    void initializeWithModernHeadersIsLegacy() {
        final var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}";
        assertThat(decide("POST", "2026-07-28", body)).isEqualTo(new McpEraDecision(McpEra.LEGACY, null));
    }

    @Test
    void initializeWithClaimIsModern() {
        final var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{" + CLAIM + "}}";
        assertThat(decide("POST", "2026-07-28", body).era()).isEqualTo(McpEra.MODERN);
    }

    @Test
    void bodyClaimIsModern() {
        assertThat(decide("POST", "2026-07-28", MODERN_LIST)).isEqualTo(new McpEraDecision(McpEra.MODERN, null));
    }

    @Test
    void legacyHeaderWithModernClaimIsModernWithCorrection() {
        assertThat(decide("POST", "2025-11-25", MODERN_LIST)).isEqualTo(new McpEraDecision(McpEra.MODERN, "2026-07-28"));
    }

    @Test
    void modernHeaderWithoutClaimIsModern() {
        assertThat(decide("POST", "2026-07-28", PLAIN_LIST)).isEqualTo(new McpEraDecision(McpEra.MODERN, null));
    }

    @Test
    void unknownFutureHeaderIsModern() {
        assertThat(decide("POST", "2099-01-01", PLAIN_LIST)).isEqualTo(new McpEraDecision(McpEra.MODERN, null));
    }

    @Test
    void mismatchingModernHeaderIsNotCorrected() {
        assertThat(decide("POST", "2099-01-01", MODERN_LIST)).isEqualTo(new McpEraDecision(McpEra.MODERN, null));
    }

    @Test
    void noHeaderWithClaimIsModernWithoutCorrection() {
        assertThat(decide("POST", null, MODERN_LIST)).isEqualTo(new McpEraDecision(McpEra.MODERN, null));
    }

    @Test
    void legacyCallsAreLegacy() {
        assertThat(decide("POST", "2025-11-25", PLAIN_LIST)).isEqualTo(new McpEraDecision(McpEra.LEGACY, null));
    }

    @Test
    void noHeaderNoClaimIsLegacy() {
        assertThat(decide("POST", null, PLAIN_LIST).era()).isEqualTo(McpEra.LEGACY);
    }

    @Test
    void jsonArrayIsLegacy() {
        assertThat(decide("POST", "2026-07-28", "[" + MODERN_LIST + "]").era()).isEqualTo(McpEra.LEGACY);
    }

    @Test
    void badJsonIsLegacy() {
        assertThat(decide("POST", "2026-07-28", "{not json").era()).isEqualTo(McpEra.LEGACY);
    }

    @Test
    void emptyBodyIsLegacy() {
        assertThat(decide("POST", "2026-07-28", "").era()).isEqualTo(McpEra.LEGACY);
    }

    @Test
    void nullBodyIsLegacy() {
        assertThat(McpEraDecision.decide("POST", "2026-07-28", null, jsonMapper).era()).isEqualTo(McpEra.LEGACY);
    }

    @Test
    void getIsLegacy() {
        assertThat(decide("GET", "2026-07-28", MODERN_LIST).era()).isEqualTo(McpEra.LEGACY);
    }

    @Test
    void nonStringClaimIsNotAClaim() {
        final var body = "{\"method\":\"tools/list\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":5}}}";
        assertThat(decide("POST", "2025-11-25", body).era()).isEqualTo(McpEra.LEGACY);
    }
}
