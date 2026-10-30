package cc.hrva.urlshortener.service.digest;

import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.WeeklyDigest.Change;
import cc.hrva.urlshortener.dto.WeeklyDigest.TopLink;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CreatedVia;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.util.DomainNames;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.lang3.StringUtils;

public final class WeeklyDigestText {

    private static final String TITLE_PREFIX = "hrva.cc weekly summary: ";
    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);
    private static final String DAYS_DASH = "–";
    private static final String MONTHS_DASH = " – ";
    private static final double PERCENT = 100.0;
    private static final long SMALLEST_BASE_FOR_A_PERCENTAGE = 20;
    private static final long LARGEST_PARTIAL_DROP = 99;
    private static final String UNKNOWN = "unknown";
    private static final String IP_DESTINATION = "an IP address";
    private static final String UNREADABLE_DESTINATION = "an unreadable destination";
    private static final Map<String, String> CHANNEL_WORDS = Map.of(
            CreatedVia.WEB.name(), "website",
            CreatedVia.API_KEY.name(), "API key",
            CreatedVia.BULK.name(), "bulk",
            CreatedVia.MCP.name(), "AI connector",
            CreatedVia.ANONYMOUS_WEB.name(), "website without an account",
            CreatedVia.ANONYMOUS_MCP.name(), "AI connector without an account",
            UNKNOWN, UNKNOWN);
    private static final Map<String, String> BLOCK_WORDS = Map.of(
            BlockReason.SAFE_BROWSING.name(), "Safe Browsing",
            BlockReason.BLOCKLIST.name(), "blocklist",
            BlockReason.DOMAIN_RULE.name(), "domain rule",
            BlockReason.SCAM_SIGNALS.name(), "scam signals",
            BlockReason.CAMPAIGN.name(), "campaign hold");

    private WeeklyDigestText() {}

    public record Line(String text, String link) {

        public String withLink(final UnaryOperator<String> linkFormat) {
            return link == null ? text : text + " " + linkFormat.apply(link);
        }

    }

    public static String title(final WeeklyDigest digest) {
        return TITLE_PREFIX + weekLabel(digest.from(), digest.to());
    }

    public static String weekLabel(final LocalDate from, final LocalDate to) {
        if (from.getYear() != to.getYear()) {
            return DAY_MONTH_YEAR.format(from) + MONTHS_DASH + DAY_MONTH_YEAR.format(to);
        }
        if (from.getMonth() != to.getMonth()) {
            return DAY_MONTH.format(from) + MONTHS_DASH + DAY_MONTH_YEAR.format(to);
        }
        return from.getDayOfMonth() + DAYS_DASH + DAY_MONTH_YEAR.format(to);
    }

    public static String change(final Change change) {
        if (change.current() == change.previous()) {
            return "same as the week before";
        }
        if (change.previous() == 0) {
            return "none the week before";
        }
        if (change.previous() < SMALLEST_BASE_FOR_A_PERCENTAGE) {
            return number(change.previous()) + " the week before";
        }
        final var percent = Math.max(1, Math.round(Math.abs(change.current() - change.previous()) * PERCENT / change.previous()));
        if (change.current() > change.previous()) {
            return "up " + number(percent) + "% on the week before";
        }
        final var shown = change.current() > 0 ? Math.min(LARGEST_PARTIAL_DROP, percent) : percent;
        return "down " + number(shown) + "% on the week before";
    }

    public static List<String> lines(final WeeklyDigest digest, final ShortLinks shortLinks, final String reviewPage,
            final UnaryOperator<String> value) {
        final var lines = new ArrayList<>(summaryLines(digest, reviewPage, UnaryOperator.identity()));
        lines.addAll(topLinkLines(digest, shortLinks, value));
        return List.copyOf(lines);
    }

    public static List<String> summaryLines(final WeeklyDigest digest, final String reviewPage, final UnaryOperator<String> linkFormat) {
        return summary(digest, reviewPage).stream().map(line -> line.withLink(linkFormat)).toList();
    }

    public static List<Line> summary(final WeeklyDigest digest, final String reviewPage) {
        final var lines = new ArrayList<Line>();
        lines.add(textLine("Clicks: " + withChange(digest.clicks())));
        lines.add(textLine("Visitors: " + number(digest.uniqueVisitors()) + " (each link's daily visitors, added up)"));
        lines.add(textLine("Bots and link previews: " + botShare(digest.clicks().current(), digest.botClicks())));
        lines.add(textLine("New links: " + withChange(digest.newLinks())));
        if (!digest.linksByChannel().isEmpty()) {
            lines.add(textLine("How they were made: " + counted(digest.linksByChannel(), CHANNEL_WORDS)));
        }
        lines.add(textLine("New users: " + withChange(digest.newUsers())));
        lines.add(textLine("Active users: " + number(digest.activeUsers()) + " signed in during the last 7 days"));
        lines.add(textLine("Reviews: " + number(digest.reviewsOpened()) + " opened, " + number(digest.reviewsResolved()) + " resolved"));
        lines.add(new Line("Review queue: " + number(digest.openReviews()) + " waiting", reviewPage));
        lines.add(textLine("Automatic blocks: " + (digest.automaticBlocks().isEmpty() ? "none" : counted(digest.automaticBlocks(), BLOCK_WORDS))));
        lines.add(textLine(digest.topLinks().isEmpty() ? "Top links by clicks: none this week" : "Top links by clicks:"));
        return List.copyOf(lines);
    }

    public static List<String> topLinkLines(final WeeklyDigest digest, final ShortLinks shortLinks, final UnaryOperator<String> value) {
        return topLinkLines(digest, shortLinks, UnaryOperator.identity(), value);
    }

    public static List<String> topLinkLines(final WeeklyDigest digest, final ShortLinks shortLinks, final UnaryOperator<String> hostname,
            final UnaryOperator<String> value) {
        final var topLinks = digest.topLinks();
        return IntStream.range(0, topLinks.size())
                .mapToObj(index -> topLink(index + 1, topLinks.get(index), shortLinks, hostname, value))
                .toList();
    }

    private static Line textLine(final String text) {
        return new Line(text, null);
    }

    private static String withChange(final Change change) {
        return number(change.current()) + " (" + change(change) + ")";
    }

    private static String botShare(final long clicks, final long botClicks) {
        final var allClicks = clicks + botClicks;
        if (allClicks == 0) {
            return "no clicks";
        }
        return String.format(Locale.ENGLISH, "%.1f%% of all clicks", botClicks * PERCENT / allClicks);
    }

    private static String counted(final List<CountEntry> entries, final Map<String, String> words) {
        return entries.stream()
                .map(entry -> number(entry.count()) + " " + wordsFor(entry.value(), words))
                .collect(Collectors.joining(", "));
    }

    private static String wordsFor(final String key, final Map<String, String> words) {
        return words.getOrDefault(key, key.toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private static String topLink(final int rank, final TopLink link, final ShortLinks shortLinks, final UnaryOperator<String> hostname,
            final UnaryOperator<String> value) {
        final var shortLink = shortLinks.display(hostname.apply(link.domain()), value.apply(link.shortUrl()));
        final var destination = value.apply(destinationOf(link.destinationHost()));
        return number(rank) + ". " + shortLink + " → " + destination + ", " + clicks(link.clicks());
    }

    private static String destinationOf(final String host) {
        if (StringUtils.isBlank(host)) {
            return UNREADABLE_DESTINATION;
        }
        return DomainNames.isIpLiteral(host) ? IP_DESTINATION : host;
    }

    private static String clicks(final long clicks) {
        return number(clicks) + (clicks == 1 ? " click" : " clicks");
    }

    private static String number(final long value) {
        return NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value);
    }

}
