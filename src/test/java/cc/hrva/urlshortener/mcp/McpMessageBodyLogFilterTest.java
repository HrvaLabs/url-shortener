package cc.hrva.urlshortener.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class McpMessageBodyLogFilterTest {

    private static final String STATELESS_TRANSPORT = "org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport";
    private static final String STREAMABLE_TRANSPORT = "org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider";
    private static final String STATELESS_HANDLER = "io.modelcontextprotocol.server.DefaultMcpStatelessServerHandler";

    private final McpMessageBodyLogFilter filter = new McpMessageBodyLogFilter();

    @Test
    void dropsUnreadableMessageLinesFromTheMcpTransports() {
        for (final var transport : new String[]{STATELESS_TRANSPORT, STREAMABLE_TRANSPORT}) {
            assertThat(decide(transport, "Failed to deserialize message: Cannot deserialize JSONRPCMessage: {\"arguments\":{}}"))
                    .isEqualTo(FilterReply.DENY);
        }
    }

    @Test
    void dropsUnknownMethodLinesThatCarryClientSuppliedMethodNames() {
        for (final var transport : new String[]{STATELESS_TRANSPORT, STREAMABLE_TRANSPORT}) {
            assertThat(decide(transport, "Failed to handle request: Missing handler for request type: secret/unknown-method"))
                    .isEqualTo(FilterReply.DENY);
        }
        assertThat(filter.decide(null, (Logger) LoggerFactory.getLogger(STATELESS_HANDLER), Level.WARN,
                "Missing handler for notification type: {}", new Object[]{"notifications/secret-unknown"}, null))
                .isEqualTo(FilterReply.DENY);
    }

    @Test
    void keepsHandlerFailuresAndOtherLoggers() {
        assertThat(decide(STATELESS_TRANSPORT, "Failed to handle request: Unknown tool")).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide(STATELESS_TRANSPORT, "Unexpected error handling message: boom")).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide(STATELESS_TRANSPORT, null)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide("cc.hrva.urlshortener.SomethingElse", "Failed to deserialize message: x")).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide("cc.hrva.urlshortener.SomethingElse", "Failed to handle request: Missing handler for request type: x")).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide(STATELESS_HANDLER, "Something else entirely: {}")).isEqualTo(FilterReply.NEUTRAL);
    }

    private FilterReply decide(final String loggerName, final String message) {
        return filter.decide(null, (Logger) LoggerFactory.getLogger(loggerName), Level.ERROR, message, null, null);
    }

}
