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
    private static final String UNREADABLE_MESSAGE = "Failed to deserialize message";

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
        if (format == null || !format.startsWith(UNREADABLE_MESSAGE) || !logger.getName().startsWith(MCP_TRANSPORT_LOGGERS)) {
            return FilterReply.NEUTRAL;
        }

        log.warn("Rejected an MCP message that is not valid JSON-RPC");

        return FilterReply.DENY;
    }

}
