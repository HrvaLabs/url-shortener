package cc.hrva.urlshortener.mcp.modern;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import java.util.List;

public final class ModernTools {

    private ModernTools() {}

    public static List<McpSyncTool> from(final List<SyncToolSpecification> legacy, final McpJsonMapper jsonMapper) {
        return legacy.stream()
                .map(specification -> convert(specification, jsonMapper))
                .toList();
    }

    private static McpSyncTool convert(final SyncToolSpecification specification, final McpJsonMapper jsonMapper) {
        final var definition = jsonMapper.convertValue(specification.tool(), Tool.class);
        return McpSyncTool.of(definition, (context, request) -> {
            final var legacyRequest = jsonMapper.convertValue(request, io.modelcontextprotocol.spec.McpSchema.CallToolRequest.class);
            final var legacyResult = specification.callHandler().apply(McpTransportContext.EMPTY, legacyRequest);
            return McpSyncResponse.result(jsonMapper.convertValue(legacyResult, CallToolResult.class));
        });
    }

}
