package cc.hrva.urlshortener.mcp.anon;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.mcp.tools.McpLinks;
import cc.hrva.urlshortener.mcp.tools.McpToolResults;
import cc.hrva.urlshortener.service.UrlService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousShortenTool {

    static final String CLIENT_IP = "clientIp";

    private static final String SHORTEN_URL = "shorten_url";
    private static final String URL = "url";
    private static final String TITLE = "Shorten a URL";
    private static final String DESCRIPTION = "Creates an anonymous hrva.cc short link and returns the full short URL. "
            + "The link belongs to no account, so it can't be listed, changed or tracked later, "
            + "and each network can create 10 links per minute. "
            + "To keep links in a hrva.cc account, with aliases, tags and visit stats, connect https://hrva.cc/mcp instead.";
    private static final String URL_DESCRIPTION = "The destination URL to shorten, for example https://example.com/page";
    private static final String RATE_LIMIT_NAME = "mcp-anon";
    private static final int RATE_LIMIT = 10;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(60);
    private static final String RATE_LIMIT_REFUSAL = "Too many anonymous links from this network. Try again in a minute, "
            + "or connect https://hrva.cc/mcp to use your account.";
    private static final Tool TOOL = Tool.builder(SHORTEN_URL, Map.of(
                    "type", "object",
                    "properties", Map.of(URL, Map.of("type", "string", "description", URL_DESCRIPTION)),
                    "required", List.of(URL)))
            .title(TITLE)
            .description(DESCRIPTION)
            .annotations(ToolAnnotations.builder()
                    .title(TITLE)
                    .readOnlyHint(false)
                    .destructiveHint(false)
                    .idempotentHint(false)
                    .openWorldHint(false)
                    .build())
            .build();

    private final UrlService urlService;
    private final RateLimiter rateLimiter;
    private final AppProperties appProperties;
    private final Validator validator;

    public SyncToolSpecification specification() {
        return SyncToolSpecification.builder()
                .tool(TOOL)
                .callHandler((context, request) -> shortenUrl(clientIpOf(context), request.arguments()))
                .build();
    }

    private CallToolResult shortenUrl(final String clientIp, final Map<String, Object> arguments) {
        return McpToolResults.run(SHORTEN_URL, () -> createLink(clientIp, urlOf(arguments)));
    }

    private String createLink(final String clientIp, final String url) {
        if (!rateLimiter.tryAcquire(RateLimiter.clientKey(RATE_LIMIT_NAME, clientIp), RATE_LIMIT, RATE_LIMIT_WINDOW)) {
            log.info("MCP anonymous shorten_url refused by the rate limit");
            throw new RateLimitExceededException(RATE_LIMIT_REFUSAL);
        }

        final var createUrlDto = CreateUrlDto.builder()
                .longUrl(url)
                .tags(McpLinks.withClaudeTag(List.of()))
                .build();
        McpLinks.validate(validator, createUrlDto);

        final var created = urlService.saveUrlRouting(createUrlDto);

        log.info("MCP anonymous shorten_url urlId={}", created.id());

        return String.join("\n",
                "Short link: " + McpLinks.shortLink(appProperties.getMcp().getIssuer(), created.shortUrl()),
                "Destination: " + created.longUrl());
    }

    private static String clientIpOf(final McpTransportContext context) {
        return context.get(CLIENT_IP) instanceof String clientIp ? clientIp : "";
    }

    private static String urlOf(final Map<String, Object> arguments) {
        return arguments != null && arguments.get(URL) instanceof String url ? url : null;
    }

}
