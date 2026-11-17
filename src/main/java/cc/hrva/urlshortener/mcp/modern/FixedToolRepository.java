package cc.hrva.urlshortener.mcp.modern;

import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import java.util.List;

public final class FixedToolRepository implements McpSyncToolRepository {

    private final List<McpSyncTool> tools;

    public FixedToolRepository(final List<McpSyncTool> tools) {
        this.tools = List.copyOf(tools);
    }

    @Override
    public ToolsPage list(final McpRequestContext context, final String cursor) {
        return ToolsPage.of(tools.stream().map(McpSyncTool::definition).toList());
    }

    @Override
    public McpSyncTool find(final McpRequestContext context, final String name) {
        return tools.stream()
                .filter(tool -> tool.definition().name().equals(name))
                .findFirst()
                .orElse(null);
    }

}
