package cc.hrva.urlshortener.service.digest;

import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.WeeklyDigest.Change;
import cc.hrva.urlshortener.dto.WeeklyDigest.TopLink;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CreatedVia;
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
            BlockReason.SCAM_SIGNALS.name(), "scam signals");

    private WeeklyDigestText() {}

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
        final var percent = Math.max(1, Math.round(Math.abs(change.current() - change.previous()) * PERCENT / change.previous()));
        final var direction = change.current() > change.previous() ? "up" : "down";
        return direction + " " + number(percent) + "% on the week before";
    }

    public static List<String> lines(final WeeklyDigest digest, final String shortLinkHost, final UnaryOperator<String> value) {
        final var lines = new ArrayList<String>();
        lines.add("Clicks: " + withChange(digest.clicks()));
        lines.add("Daily unique visitors, added up: " + number(digest.uniqueVisitors()));
        lines.add("Bot share: " + botShare(digest.clicks().current(), digest.botClicks()));
        lines.add("New links: " + withChange(digest.newLinks()));
        if (!digest.linksByChannel().isEmpty()) {
            lines.add("How they were made: " + counted(digest.linksByChannel(), CHANNEL_WORDS));
        }
        lines.add("New users: " + withChange(digest.newUsers()));
        lines.add("Active users: " + number(digest.activeUsers()) + " signed in during the last 7 days");
        lines.add("Reviews: " + number(digest.reviewsOpened()) + " opened, " + number(digest.reviewsResolved()) + " resolved");
        lines.add("Automatic blocks: " + (digest.automaticBlocks().isEmpty() ? "none" : counted(digest.automaticBlocks(), BLOCK_WORDS)));
        if (digest.topLinks().isEmpty()) {
            lines.add("Top links by clicks: none this week");
        } else {
            lines.add("Top links by clicks:");
            final var topLinks = digest.topLinks();
            IntStream.range(0, topLinks.size())
                    .mapToObj(index -> topLink(index + 1, topLinks.get(index), shortLinkHost, value))
                    .forEach(lines::add);
        }
        return List.copyOf(lines);
    }

    private static String withChange(final Change change) {
        return number(change.current()) + " (" + change(change) + ")";
    }

    private static String botShare(final long clicks, final long botClicks) {
        final var allClicks = clicks + botClicks;
        if (allClicks == 0) {
            return "no clicks";
        }
        return String.format(Locale.ENGLISH, "%.1f%%", botClicks * PERCENT / allClicks);
    }

    private static String counted(final List<CountEntry> entries, final Map<String, String> words) {
        return entries.stream()
                .map(entry -> number(entry.count()) + " " + wordsFor(entry.value(), words))
                .collect(Collectors.joining(", "));
    }

    private static String wordsFor(final String key, final Map<String, String> words) {
        return words.getOrDefault(key, key.toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private static String topLink(final int rank, final TopLink link, final String shortLinkHost, final UnaryOperator<String> value) {
        final var shortLink = value.apply(shortLinkHost + "/" + link.shortUrl());
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
