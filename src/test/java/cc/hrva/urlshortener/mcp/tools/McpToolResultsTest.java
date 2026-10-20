package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.exception.UrlValidationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class McpToolResultsTest {

    @Test
    void successBecomesTextResult() {
        final var result = McpToolResults.run("some_tool", () -> "done");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("done");
    }

    @Test
    void backendExceptionBecomesToolErrorWithItsMessage() {
        final var result = McpToolResults.run("some_tool", () -> {
            throw new UrlValidationException("Expiration date can't be in the past");
        });

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("Expiration date can't be in the past");
    }

    @Test
    void mcpProtocolErrorIsRethrownUnchanged() {
        final var protocolError = McpError.builder(ErrorCodes.INVALID_PARAMS).message("Invalid params").build();

        final var thrown = catchThrowable(() -> McpToolResults.run("some_tool", () -> {
            throw protocolError;
        }));

        assertThat(thrown).isSameAs(protocolError);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void unexpectedExceptionBecomesGenericErrorAndLogsOnlyToolAndClass(final CapturedOutput output) {
        final var result = McpToolResults.run("some_tool", () -> {
            throw new IllegalStateException("secret-detail");
        });

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo(McpToolResults.GENERIC_ERROR);
        assertThat(output)
                .contains("tool=some_tool", "exception=java.lang.IllegalStateException")
                .doesNotContain("secret-detail");
    }

    private static String text(final CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

}
