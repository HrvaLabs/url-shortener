package cc.hrva.urlshortener.mcp.admin;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import java.util.List;

public record AdminMcpTools(List<SyncToolSpecification> specifications) {

    public AdminMcpTools {
        specifications = List.copyOf(specifications);
    }

}
