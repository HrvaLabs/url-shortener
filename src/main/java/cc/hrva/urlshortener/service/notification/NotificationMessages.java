package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.notification.DailySummary;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationMessages {

    public static final int MOST_CHARACTERS = 1900;
    public static final int MOST_LINKS = 20;
    public static final String TEST_TEXT = "This is a test from hrva.cc. Your channel works.";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);
    private static final String ARROW = " " + Character.toString(0x2192) + " ";
    private static final String UNREADABLE_DESTINATION = "an unreadable destination";
    private static final String DASHBOARD = "/dashboard";
    private static final String SETTINGS = "/dashboard/settings#notifications";
    private static final String LINE_SEPARATOR = "\n";
    private static final String CLICK = "click";
    private static final String VISITOR = "visitor";
    private static final String BOT_CLICK = "bot click";

    private final AppProperties appProperties;
    private final Clock clock;

    public record Format(UnaryOperator<String> title, UnaryOperator<String> line, UnaryOperator<String> link, Function<Integer, String> more) {

        public static final Format PLAIN = new Format(UnaryOperator.identity(), UnaryOperator.identity(), UnaryOperator.identity(),
                hidden -> "and " + hidden + " more:");

    }

    public NotificationMessage linkDead(final List<DeadLink> links) {
        final var title = links.size() == 1 ? "hrva.cc: 1 link looks broken" : "hrva.cc: %d links look broken".formatted(links.size());
        return listed(title, links, link -> target(link) + ", since " + dayText(link.deadSince()) + ", " + DestinationStatusWords.of(link.lastStatus()));
    }

    public NotificationMessage linkBack(final List<DeadLink> links) {
        final var title = links.size() == 1 ? "hrva.cc: 1 link works again" : "hrva.cc: %d links work again".formatted(links.size());
        return listed(title, links, link -> target(link) + ", works again after looking broken since " + dayText(link.deadSince()));
    }

    public NotificationMessage daily(final DailySummary summary) {
        final var lines = new ArrayList<String>();
        lines.add(counted(summary.clicks(), CLICK) + " from " + counted(summary.uniqueVisitors(), VISITOR) + ", and "
                + counted(summary.botClicks(), BOT_CLICK));
        summary.links().stream()
                .limit(MOST_LINKS)
                .map(link -> target(link.shortUrl(), link.destinationHost()) + ": " + counted(link.clicks(), CLICK) + ", "
                        + counted(link.uniqueVisitors(), VISITOR) + ", " + counted(link.botClicks(), BOT_CLICK))
                .forEach(lines::add);
        return new NotificationMessage("hrva.cc: clicks on " + DAY.format(summary.date()), lines, frontendPage(DASHBOARD), 0);
    }

    public NotificationMessage test(final NotificationChannel channel) {
        return new NotificationMessage("hrva.cc test: this channel works", List.of(TEST_TEXT, "Channel: " + channel.getName()), frontendPage(SETTINGS), 0);
    }

    public String render(final NotificationMessage message) {
        return fitted(message, Format.PLAIN);
    }

    public static String fitted(final NotificationMessage message, final Format format) {
        final var lines = message.lines().stream().map(format.line()).toList();
        var shown = lines.size();
        var text = joined(message, format, lines, shown);
        while (text.length() > MOST_CHARACTERS && shown > 0) {
            shown--;
            text = joined(message, format, lines, shown);
        }
        return text;
    }

    public LocalDate day(final LocalDateTime stored) {
        return stored.atZone(clock.getZone()).withZoneSameInstant(appProperties.getAnalytics().getZone()).toLocalDate();
    }

    public String dayText(final LocalDateTime stored) {
        return DAY.format(day(stored));
    }

    public String target(final DeadLink link) {
        return target(link.shortUrl(), link.destinationHost());
    }

    public String target(final String alias, final String destinationHost) {
        final var host = StringUtils.isBlank(destinationHost) ? UNREADABLE_DESTINATION : destinationHost;
        return shortLinkHost() + "/" + alias + ARROW + host;
    }

    public String shortLinkHost() {
        final var serverUrl = StringUtils.removeEnd(StringUtils.defaultString(appProperties.getServerUrl()), "/");
        return serverUrl.contains("://") ? StringUtils.substringAfter(serverUrl, "://") : serverUrl;
    }

    public String frontendPage(final String path) {
        return StringUtils.removeEnd(StringUtils.defaultString(appProperties.getFrontendUrl()), "/") + path;
    }

    private NotificationMessage listed(final String title, final List<DeadLink> links, final Function<DeadLink, String> line) {
        final var lines = links.stream().limit(MOST_LINKS).map(line).toList();
        return new NotificationMessage(title, lines, frontendPage(DASHBOARD), links.size() - lines.size());
    }


    public static String counted(final long count, final String noun) {
        return NumberFormat.getIntegerInstance(Locale.ENGLISH).format(count) + " " + noun + (count == 1 ? "" : "s");
    }

    private static String joined(final NotificationMessage message, final Format format, final List<String> lines, final int shown) {
        final var parts = new ArrayList<String>();
        parts.add(format.title().apply(message.title()));
        parts.addAll(lines.subList(0, shown));
        final var hidden = lines.size() - shown + message.moreCount();
        final var link = message.link() == null ? null : format.link().apply(message.link());
        if (hidden > 0) {
            parts.add(link == null ? format.more().apply(hidden) : format.more().apply(hidden) + " " + link);
        } else if (link != null) {
            parts.add(link);
        }
        return String.join(LINE_SEPARATOR, parts);
    }

}
