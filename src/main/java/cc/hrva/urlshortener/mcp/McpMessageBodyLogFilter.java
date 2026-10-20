package cc.hrva.urlshortener.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class McpMessageBodyLogFilter extends TurboFilter {

    private static final String MCP_TRANSPORT_LOGGERS = "org.springframework.ai.mcp.server.webmvc.transport.";
    private static final String STATELESS_HANDLER_LOGGER = "io.modelcontextprotocol.server.DefaultMcpStatelessServerHandler";
    private static final String UNREADABLE_MESSAGE = "Failed to deserialize message";
    private static final String UNKNOWN_REQUEST_METHOD = "Failed to handle request: Missing handler for request type:";
    private static final String UNKNOWN_NOTIFICATION_METHOD = "Missing handler for notification type:";

    @PostConstruct
    void register() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext) {
            loggerContext.getTurboFilterList().removeIf(McpMessageBodyLogFilter.class::isInstance);
            setContext(loggerContext);
            start();
            loggerContext.addTurboFilter(this);
        }
    }

    @PreDestroy
    void unregister() {
        if (getContext() instanceof LoggerContext loggerContext) {
            loggerContext.getTurboFilterList().remove(this);
        }
        stop();
    }

    @Override
    public FilterReply decide(final Marker marker, final Logger logger, final Level level, final String format,
                              final Object[] params, final Throwable throwable) {
        if (format == null) {
            return FilterReply.NEUTRAL;
        }

        if (format.startsWith(UNREADABLE_MESSAGE) && logger.getName().startsWith(MCP_TRANSPORT_LOGGERS)) {
            log.warn("Rejected an MCP message that is not valid JSON-RPC");
            return FilterReply.DENY;
        }

        if (format.startsWith(UNKNOWN_REQUEST_METHOD) && logger.getName().startsWith(MCP_TRANSPORT_LOGGERS)) {
            log.warn("Rejected an MCP request for an unknown method");
            return FilterReply.DENY;
        }

        if (format.startsWith(UNKNOWN_NOTIFICATION_METHOD) && STATELESS_HANDLER_LOGGER.equals(logger.getName())) {
            log.debug("Ignored an MCP notification without a handler");
            return FilterReply.DENY;
        }

        return FilterReply.NEUTRAL;
    }

}
