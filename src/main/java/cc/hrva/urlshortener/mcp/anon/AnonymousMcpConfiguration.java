package cc.hrva.urlshortener.mcp.anon;

import cc.hrva.urlshortener.security.ClientIpResolver;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@RequiredArgsConstructor
public class AnonymousMcpConfiguration {

    private static final String ANONYMOUS_MCP_ENDPOINT = "/mcp/anon";
    private static final String SERVER_NAME = "hrva.cc anonymous";
    private static final String SERVER_VERSION = "1.0.0";

    private final ClientIpResolver clientIpResolver;
    private final AnonymousShortenTool anonymousShortenTool;

    @Bean
    @Order(3)
    public SecurityFilterChain anonymousMcpSecurityFilterChain(final HttpSecurity http) throws Exception {
        http.securityMatcher(ANONYMOUS_MCP_ENDPOINT)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public RouterFunction<ServerResponse> anonymousMcpRouterFunction(@Qualifier("mcpServerJsonMapper") final JsonMapper jsonMapper) {
        final var mcpJsonMapper = new JacksonMcpJsonMapper(jsonMapper);
        final var transport = WebMvcStatelessServerTransport.builder()
                .jsonMapper(mcpJsonMapper)
                .messageEndpoint(ANONYMOUS_MCP_ENDPOINT)
                .contextExtractor(this::clientContext)
                .build();

        McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .jsonMapper(mcpJsonMapper)
                .immediateExecution(true)
                .validateToolInputs(false)
                .tools(anonymousShortenTool.specification())
                .build();

        return transport.getRouterFunction()
                .andRoute(RequestPredicates.path(ANONYMOUS_MCP_ENDPOINT), AnonymousMcpConfiguration::methodNotAllowed);
    }

    private static ServerResponse methodNotAllowed(final ServerRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.method().name(), List.of(HttpMethod.POST.name()));
    }

    private McpTransportContext clientContext(final ServerRequest request) {
        final var clientIp = Objects.requireNonNullElse(clientIpResolver.getClientIp(request.servletRequest()), "");

        return McpTransportContext.create(Map.of(AnonymousShortenTool.CLIENT_IP, clientIp));
    }

}
