package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.exception.CommonException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public final class McpToolResults {

    public static final String GENERIC_ERROR = "Something went wrong on hrva.cc. Try again in a moment.";

    private McpToolResults() {}

    public static CallToolResult run(final String toolName, final Supplier<String> tool) {
        try {
            return text(tool.get());
        } catch (final CommonException exception) {
            return error(StringUtils.defaultIfBlank(exception.getMessage(), GENERIC_ERROR));
        } catch (final RuntimeException exception) {
            log.error("MCP tool failed tool={} exception={}", toolName, exception.getClass().getName());
            return error(GENERIC_ERROR);
        }
    }

    private static CallToolResult text(final String text) {
        return CallToolResult.builder()
                .addTextContent(text)
                .isError(false)
                .build();
    }

    private static CallToolResult error(final String text) {
        return CallToolResult.builder()
                .addTextContent(text)
                .isError(true)
                .build();
    }

}
