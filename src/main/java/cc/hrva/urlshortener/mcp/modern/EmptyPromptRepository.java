package cc.hrva.urlshortener.mcp.modern;

import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.feature.McpSyncPrompt;
import io.modelcontextprotocol.modern.server.feature.McpSyncPromptRepository;
import io.modelcontextprotocol.modern.server.feature.PromptsPage;
import java.util.List;

public final class EmptyPromptRepository implements McpSyncPromptRepository {

    @Override
    public PromptsPage list(final McpRequestContext context, final String cursor) {
        return PromptsPage.of(List.of());
    }

    @Override
    public McpSyncPrompt find(final McpRequestContext context, final String name) {
        return null;
    }

}
