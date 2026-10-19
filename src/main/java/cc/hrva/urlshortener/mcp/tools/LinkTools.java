package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.service.UrlService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LinkTools {

    private static final String LIST_LINKS = "list_links";
    private static final int PAGE_SIZE = 20;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createDate");

    private final UrlService urlService;
    private final AppProperties appProperties;
    private final McpUserResolver mcpUserResolver;

    @McpTool(
            name = LIST_LINKS,
            title = "List my links",
            description = "Lists the signed-in user's hrva.cc short links, newest first, 20 per page. "
                    + "Each line shows the short link, its destination, its visit count and its status.",
            annotations = @McpTool.McpAnnotations(
                    title = "List my links",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public CallToolResult listLinks(
            @McpToolParam(required = false, description = "Only links whose destination or alias contains this text") final String search,
            @McpToolParam(required = false, description = "Only links with this tag") final String tag,
            @McpToolParam(required = false, description = "Zero-based page number, 0 by default") final Integer page) {
        return McpToolResults.run(LIST_LINKS, () -> describeLinks(search, tag, page));
    }

    private String describeLinks(final String search, final String tag, final Integer page) {
        final var user = mcpUserResolver.currentUser();
        final var pageable = PageRequest.of(Math.max(0, Objects.requireNonNullElse(page, 0)), PAGE_SIZE, NEWEST_FIRST);
        final var links = urlService.getAllMyUrls(null, pageable, new UrlSearchDto(search, null, null, tag, null, null));

        log.info("MCP list_links userId={} results={}", user.getId(), links.getNumberOfElements());

        if (links.isEmpty()) {
            return "No links found.";
        }

        return links.stream()
                .map(this::describe)
                .collect(Collectors.joining("\n"));
    }

    private String describe(final UrlResponse link) {
        return "%s/%s -> %s | %d visits | %s".formatted(
                appProperties.getMcp().getIssuer(),
                link.shortUrl(),
                link.longUrl(),
                Objects.requireNonNullElse(link.visits(), 0L),
                link.status()
        );
    }

}
