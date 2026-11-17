package cc.hrva.urlshortener.mcp;

import cc.hrva.urlshortener.mcp.admin.AdminMcpServerConfiguration;
import cc.hrva.urlshortener.mcp.modern.ModernMcpTransports;
import cc.hrva.urlshortener.service.SettingsService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class McpEraFilterConfiguration {

    private static final String MCP_ENDPOINT = "/mcp";
    private static final int ERA_FILTER_ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER + 100;

    @Bean
    public FilterRegistrationBean<McpEraFilter> mcpEraFilterRegistration(final ModernMcpTransports modernMcpTransports,
                                                                        final SettingsService settingsService, final JsonMapper jsonMapper,
                                                                        final MeterRegistry meterRegistry) {
        final var registration = new FilterRegistrationBean<>(new McpEraFilter(modernMcpTransports, settingsService, jsonMapper, meterRegistry));
        registration.addUrlPatterns(MCP_ENDPOINT, AdminMcpServerConfiguration.ENDPOINT);
        registration.setOrder(ERA_FILTER_ORDER);

        return registration;
    }

}
