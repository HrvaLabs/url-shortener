package cc.hrva.urlshortener.mcp.admin;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.tools.McpUserResolver;
import cc.hrva.urlshortener.mcp.tools.ModerationTools;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@RequiredArgsConstructor
public class AdminMcpServerConfiguration {

    public static final String ENDPOINT = "/mcp/admin";

    private final McpServerProperties serverProperties;
    private final McpUserResolver mcpUserResolver;
    private final ModerationService moderationService;
    private final ReviewQueueService reviewQueueService;
    private final ScammerSignalService scammerSignalService;
    private final LinkCheckService linkCheckService;
    private final BlocklistSourceService blocklistSourceService;
    private final UserRepository userRepository;
    private final UrlRepository urlRepository;
    private final LinkReviewRepository linkReviewRepository;
    private final AppProperties appProperties;
    private final Clock clock;
    private final ShortLinks shortLinks;

    @Bean
    public RouterFunction<ServerResponse> adminMcpRouterFunction(@Qualifier("mcpServerJsonMapper") final JsonMapper jsonMapper) {
        final var transport = WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .messageEndpoint(ENDPOINT)
                .build();

        McpServer.sync(transport)
                .serverInfo(serverProperties.getName(), serverProperties.getVersion())
                .capabilities(capabilities())
                .tools(SyncMcpAnnotationProviders.statelessToolSpecifications(List.of(moderationTools())))
                .requestTimeout(serverProperties.getRequestTimeout())
                .immediateExecution(true)
                .build();

        return transport.getRouterFunction();
    }

    private ModerationTools moderationTools() {
        return new ModerationTools(mcpUserResolver, moderationService, reviewQueueService, scammerSignalService, linkCheckService,
                blocklistSourceService, userRepository, urlRepository, linkReviewRepository, appProperties, clock, shortLinks);
    }

    private McpSchema.ServerCapabilities capabilities() {
        final var capabilities = McpSchema.ServerCapabilities.builder().tools(false);
        if (serverProperties.getCapabilities().isResource()) {
            capabilities.resources(false, false);
        }
        if (serverProperties.getCapabilities().isPrompt()) {
            capabilities.prompts(false);
        }

        return capabilities.build();
    }

}
