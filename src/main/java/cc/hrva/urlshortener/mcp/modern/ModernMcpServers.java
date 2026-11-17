package cc.hrva.urlshortener.mcp.modern;

import cc.hrva.urlshortener.mcp.McpRequestBodyLimitFilter;
import cc.hrva.urlshortener.mcp.admin.AdminMcpServerConfiguration;
import cc.hrva.urlshortener.mcp.admin.AdminMcpTools;
import cc.hrva.urlshortener.mcp.tools.LinkTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.server.HmacRequestStateCodec;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.PromptsFeature;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@RequiredArgsConstructor
public class ModernMcpServers {

    private static final String MCP_ENDPOINT = "/mcp";
    private static final List<String> SUPPORTED_VERSIONS = List.of("2026-07-28");
    private static final long CACHE_TTL_MS = Duration.ofMinutes(5).toMillis();
    private static final int REQUEST_STATE_KEY_BYTES = 32;

    private final McpServerProperties serverProperties;

    @Bean
    public ModernMcpTransports modernMcpTransports(@Qualifier("mcpServerJsonMapper") final JsonMapper jsonMapper, final LinkTools linkTools,
                                                   final AdminMcpTools adminMcpTools) {
        final var mcpJsonMapper = new JacksonMcpJsonMapper(jsonMapper);
        final var linkToolSpecifications = SyncMcpAnnotationProviders.statelessToolSpecifications(List.of(linkTools));

        return new ModernMcpTransports(Map.of(
                MCP_ENDPOINT, transport(MCP_ENDPOINT, linkToolSpecifications, mcpJsonMapper),
                AdminMcpServerConfiguration.ENDPOINT, transport(AdminMcpServerConfiguration.ENDPOINT, adminMcpTools.specifications(), mcpJsonMapper)));
    }

    private HttpServletMcpTransport transport(final String endpoint, final List<SyncToolSpecification> tools, final McpJsonMapper jsonMapper) {
        final var server = McpServer.builder()
                .serverInfo(Implementation.builder(serverProperties.getName(), serverProperties.getVersion()).build())
                .instructions(serverProperties.getInstructions())
                .supportedVersions(SUPPORTED_VERSIONS)
                .jsonMapper(jsonMapper)
                .feature(ToolsFeature.ofSync(new FixedToolRepository(ModernTools.from(tools, jsonMapper)), jsonMapper,
                        McpJsonDefaults.getSchemaValidator(), CACHE_TTL_MS, CacheScope.PRIVATE))
                .feature(ResourcesFeature.ofSync(new EmptyResourceRepository(), jsonMapper, CACHE_TTL_MS, CacheScope.PRIVATE))
                .feature(PromptsFeature.ofSync(new EmptyPromptRepository(), jsonMapper, CACHE_TTL_MS, CacheScope.PRIVATE))
                .discoverCache(CACHE_TTL_MS, CacheScope.PRIVATE)
                .requestStateCodec(HmacRequestStateCodec.builder()
                        .jsonMapper(jsonMapper)
                        .key(requestStateKey())
                        .build())
                .build();

        return HttpServletMcpTransport.builder(server)
                .endpoint(endpoint)
                .jsonMapper(jsonMapper)
                .maxRequestSize(McpRequestBodyLimitFilter.MAX_BODY_BYTES)
                .httpHeaderValidator(ServerHttpHeaderValidator.NOOP)
                .build();
    }

    private static byte[] requestStateKey() {
        final var key = new byte[REQUEST_STATE_KEY_BYTES];
        new SecureRandom().nextBytes(key);

        return key;
    }

}
