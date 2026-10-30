package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.DomainRef;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.enums.CreatedVia;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LinkTools {

    private static final String LIST_LINKS = "list_links";
    private static final String SHORTEN_URL = "shorten_url";
    private static final String LINK_STATS = "link_stats";
    private static final int PAGE_SIZE = 20;
    private static final int TOP_VALUES_SHOWN = 5;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createDate");
    private static final String EXPIRY_FORMAT_ERROR = "expiresAt must be an ISO-8601 date-time with a time zone, "
            + "for example 2026-12-31T23:59:00Z or 2026-12-31T23:59:00+01:00.";
    private static final String MISSING_LINK_ERROR = "Give the short link, for example menu, hrva.cc/menu or https://hrva.cc/menu.";
    private static final String DAILY_UNIQUE_NOTE = "Unique visitors are counted per day, so one person on two different days counts twice. "
            + "Bot clicks aren't in the clicks or visitor counts.";

    private final UrlService urlService;
    private final AppProperties appProperties;
    private final McpUserResolver mcpUserResolver;
    private final AnalyticsService analyticsService;
    private final Validator validator;
    private final ShortLinks shortLinks;

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

    @McpTool(
            name = SHORTEN_URL,
            title = "Shorten a URL",
            description = "Creates a hrva.cc short link in the signed-in user's account. "
                    + "Returns the full short URL and its destination, plus the visit limit and expiry when set. "
                    + "Every link created here gets the tag ai.",
            annotations = @McpTool.McpAnnotations(
                    title = "Shorten a URL",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = false
            )
    )
    public CallToolResult shortenUrl(
            @McpToolParam(description = "The destination URL to shorten, for example https://example.com/page") final String url,
            @McpToolParam(required = false, description = "Custom alias for the short link: letters, numbers, hyphens and underscores, "
                    + "up to 32 characters. A random alias is generated when omitted.") final String alias,
            @McpToolParam(required = false, description = "Number of visits after which the link stops working") final Long visitLimit,
            @McpToolParam(required = false, description = "When the link stops working, as an ISO-8601 date-time with a time zone, "
                    + "for example 2026-12-31T23:59:00Z or 2026-12-31T23:59:00+01:00") final String expiresAt,
            @McpToolParam(required = false, description = "Tags for the link. The tag ai is always added, "
                    + "and a link keeps at most 10 tags of up to 30 characters each.") final List<String> tags) {
        return McpToolResults.run(SHORTEN_URL, () -> createLink(url, alias, visitLimit, expiresAt, tags));
    }

    @McpTool(
            name = LINK_STATS,
            title = "Link stats",
            description = "Shows click statistics for one of the signed-in user's hrva.cc short links over the last 7, 30, 90 or 365 days "
                    + "(30 by default): clicks, daily unique visitors, bot clicks, top referrers, countries and devices, all-time visits, "
                    + "last visit and creation time (times in UTC).",
            annotations = @McpTool.McpAnnotations(
                    title = "Link stats",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public CallToolResult linkStats(
            @McpToolParam(description = "The short link as menu, hrva.cc/menu or https://hrva.cc/menu, "
                    + "or as go.example.com/menu for a link on one of your own domains") final String link,
            @McpToolParam(required = false, description = "Number of days to cover: 7, 30, 90 or 365 (30 by default)") final Integer days) {
        return McpToolResults.run(LINK_STATS, () -> describeStats(link, days));
    }

    private String describeLinks(final String search, final String tag, final Integer page) {
        final var user = mcpUserResolver.currentUser();
        final var pageable = PageRequest.of(Math.max(0, Objects.requireNonNullElse(page, 0)), PAGE_SIZE, NEWEST_FIRST);
        final var links = urlService.getAllMyUrls(null, pageable, new UrlSearchDto(search, null, null, tag, null, null, null, null));

        log.info("MCP list_links userId={} results={}", user.getId(), links.getNumberOfElements());

        if (links.isEmpty()) {
            return "No links found.";
        }

        return links.stream()
                .map(this::describe)
                .collect(Collectors.joining("\n"));
    }

    private String describe(final UrlResponse link) {
        return "%s -> %s | %d visits | %s".formatted(
                shortLink(link.domain(), link.shortUrl()),
                link.longUrl(),
                Objects.requireNonNullElse(link.visits(), 0L),
                link.status()
        );
    }

    private String createLink(final String url, final String alias, final Long visitLimit, final String expiresAt, final List<String> tags) {
        final var user = mcpUserResolver.currentUser();
        final var createUrlDto = CreateUrlDto.builder()
                .longUrl(url)
                .shortUrl(StringUtils.trimToNull(alias))
                .visitLimit(visitLimit)
                .expirationDate(parseExpiry(expiresAt))
                .tags(McpLinks.withAiTag(tags))
                .build();
        McpLinks.validate(validator, createUrlDto);

        final var created = urlService.saveUrlRouting(createUrlDto, null, CreatedVia.MCP);

        log.info("MCP shorten_url userId={} urlId={}", user.getId(), created.id());

        return describeCreated(created);
    }

    private static LocalDateTime parseExpiry(final String expiresAt) {
        if (StringUtils.isBlank(expiresAt)) {
            return null;
        }
        try {
            return ZonedDateTime.parse(expiresAt.trim())
                    .withZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (final DateTimeParseException exception) {
            throw new UrlValidationException(EXPIRY_FORMAT_ERROR);
        }
    }

    private String describeCreated(final UrlResponse link) {
        final var lines = new ArrayList<String>();
        lines.add("Short link: " + shortLink(link.domain(), link.shortUrl()));
        lines.add("Destination: " + link.longUrl());
        if (link.visitLimit() != null) {
            lines.add("Visit limit: " + link.visitLimit());
        }
        if (link.expirationDate() != null) {
            lines.add("Expires: " + utc(link.expirationDate()));
        }
        return String.join("\n", lines);
    }

    private String describeStats(final String link, final Integer days) {
        final var user = mcpUserResolver.currentUser();
        final var requested = McpLinks.requestedLink(link, host());
        if (requested.alias().isEmpty()) {
            throw new UrlValidationException(MISSING_LINK_ERROR);
        }

        final var match = findOwnLink(requested).orElseThrow(() -> new UrlNotFoundException("No link %s/%s in your account.".formatted(
                Objects.requireNonNullElseGet(requested.hostname(), this::host), requested.alias())));
        final var analytics = analyticsService.getUrlAnalyticsForOwner(match.id(), days);

        log.info("MCP link_stats userId={} urlId={}", user.getId(), match.id());

        return describeAnalytics(analytics);
    }

    private Optional<UrlResponse> findOwnLink(final McpLinks.RequestedLink requested) {
        final var search = new UrlSearchDto(requested.alias(), null, null, null, null, null, null, null);
        return urlService.getAllMyUrls(null, Pageable.unpaged(NEWEST_FIRST), search).stream()
                .filter(candidate -> requested.alias().equals(candidate.shortUrl()))
                .filter(candidate -> requested.matches(candidate.domain()))
                .sorted(Comparator.comparing((UrlResponse candidate) -> !candidate.active()).thenComparing(candidate -> candidate.domain() != null))
                .findFirst();
    }

    private String describeAnalytics(final UrlAnalyticsDto analytics) {
        final var link = analytics.link();
        final var totals = analytics.totals();
        final var breakdowns = analytics.breakdowns();
        return String.join("\n",
                "%s -> %s".formatted(shortLink(link.domain(), link.shortUrl()), link.destinationHost()),
                "Status: " + link.status(),
                "Range: last %d days, %s to %s".formatted(analytics.days(), analytics.from(), analytics.to()),
                "Clicks: " + totals.clicks(),
                "Daily unique visitors: " + totals.uniqueVisitors(),
                "Bot clicks: " + totals.botClicks(),
                "Top referrers: " + topValues(breakdowns.referrers()),
                "Top countries: " + topValues(breakdowns.countries()),
                "Devices: " + topValues(breakdowns.devices()),
                "All-time visits: " + link.visits(),
                "Last visit: " + (link.lastAccessed() != null ? utc(link.lastAccessed()) : "none"),
                "Created: " + (link.createDate() != null ? utc(link.createDate()) : "unknown"),
                DAILY_UNIQUE_NOTE);
    }

    private static String topValues(final List<BreakdownEntry> entries) {
        if (entries.isEmpty()) {
            return "none";
        }
        return entries.stream()
                .limit(TOP_VALUES_SHOWN)
                .map(entry -> entry.value() + " " + entry.clicks())
                .collect(Collectors.joining(", "));
    }

    private String shortLink(final DomainRef domain, final String alias) {
        return McpLinks.shortLink(appProperties.getMcp().getIssuer(), shortLinks, domain == null ? null : domain.hostname(), alias);
    }

    private String host() {
        return URI.create(appProperties.getMcp().getIssuer()).getRawAuthority();
    }

    private static String utc(final LocalDateTime systemTime) {
        return systemTime.truncatedTo(ChronoUnit.SECONDS)
                .atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

}
