package cc.hrva.urlshortener.mcp.modern;

import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.feature.McpSyncResource;
import io.modelcontextprotocol.modern.server.feature.McpSyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.ResourcesPage;
import java.util.List;

public final class EmptyResourceRepository implements McpSyncResourceRepository {

    @Override
    public ResourcesPage list(final McpRequestContext context, final String cursor) {
        return ResourcesPage.of(List.of());
    }

    @Override
    public McpSyncResource find(final McpRequestContext context, final String uri) {
        return null;
    }

}
