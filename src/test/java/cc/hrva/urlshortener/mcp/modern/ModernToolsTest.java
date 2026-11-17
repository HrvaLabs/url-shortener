package cc.hrva.urlshortener.mcp.modern;

import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.mcp.tools.McpToolResults;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ModernToolsTest {

    private final JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    private final List<SyncToolSpecification> legacy = SyncMcpAnnotationProviders.statelessToolSpecifications(List.of(new SampleTools()));
    private final List<io.modelcontextprotocol.modern.server.feature.McpSyncTool> modern = ModernTools.from(legacy, jsonMapper);

    @Test
    void definitionsKeepNameTitleDescriptionSchemaAndAnnotations() throws Exception {
        assertThat(modern).hasSize(2);
        for (var index = 0; index < legacy.size(); index++) {
            final var expected = jsonMapper.readValue(jsonMapper.writeValueAsString(legacy.get(index).tool()), Map.class);
            final var actual = jsonMapper.readValue(jsonMapper.writeValueAsString(modern.get(index).definition()), Map.class);
            assertThat(actual).isEqualTo(expected);
        }
        final var first = named("say_hello").definition();
        assertThat(first.name()).isEqualTo("say_hello");
        assertThat(first.title()).isEqualTo("Say hello");
        assertThat(first.description()).isEqualTo("Says hello");
        assertThat(first.annotations().readOnlyHint()).isTrue();
        assertThat(first.inputSchema()).containsKey("properties");
    }

    @Test
    void callReturnsTheLegacyResultAsComplete() {
        final var result = call("say_hello");

        assertThat(result.resultType()).isEqualTo(ResultType.COMPLETE);
        assertThat(result.isError()).isFalse();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("hello y");
    }

    @Test
    void toolErrorStaysAToolError() {
        final var result = call("fail_politely");

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("That link is not yours");
    }

    @Test
    void repositoryListsInOrderAndFindsByName() {
        final var repository = new FixedToolRepository(modern);

        final var page = repository.list(null, null);

        assertThat(page.tools()).extracting(tool -> tool.name())
                .containsExactlyElementsOf(legacy.stream().map(specification -> specification.tool().name()).toList());
        assertThat(page.nextCursor()).isNull();
        assertThat(repository.find(null, "fail_politely")).isSameAs(named("fail_politely"));
        assertThat(repository.find(null, "missing")).isNull();
    }

    private io.modelcontextprotocol.modern.server.feature.McpSyncTool named(final String name) {
        return modern.stream().filter(tool -> tool.definition().name().equals(name)).findFirst().orElseThrow();
    }

    private CallToolResult call(final String name) {
        final var request = CallToolRequest.builder(name)
                .arguments(Map.of("x", "y"))
                .build();
        final var response = (McpSyncResponse.Result<?>) named(name).handler().call(null, request);
        return (CallToolResult) response.result();
    }

    static class SampleTools {

        @McpTool(name = "say_hello", title = "Say hello", description = "Says hello",
                annotations = @McpTool.McpAnnotations(title = "Say hello", readOnlyHint = true, destructiveHint = false))
        public io.modelcontextprotocol.spec.McpSchema.CallToolResult sayHello(
                @McpToolParam(description = "Who", required = false) final String x) {
            return McpToolResults.run("say_hello", () -> "hello " + x);
        }

        @McpTool(name = "fail_politely", description = "Fails")
        public io.modelcontextprotocol.spec.McpSchema.CallToolResult failPolitely(
                @McpToolParam(description = "Who", required = false) final String x) {
            return McpToolResults.run("fail_politely", () -> {
                throw new UrlValidationException("That link is not yours");
            });
        }

    }

}
