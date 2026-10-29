package cc.hrva.urlshortener.service.alert;

import cc.hrva.urlshortener.client.DiscordMessage;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BioPageReported;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BioPageReviewOpened;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BlocklistSourceFailed;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BlocklistSourceRecovered;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BurstDetected;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.LinkBlocked;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.ReviewOpened;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.UserSignedUp;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.VisitorReported;
import cc.hrva.urlshortener.service.digest.WeeklyDigestText;
import cc.hrva.urlshortener.util.DomainNames;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DiscordAlertFormatter {

    private static final int MAX_LINES = 10;
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final String LINE_SEPARATOR = "\n";
    private static final String REVIEW_PAGE = "/admin/review";
    private static final String LINKS_PAGE = "/admin/urls";
    private static final String USERS_PAGE = "/admin";
    private static final String BLOCKLISTS_PAGE = "/admin/blocklists";
    private static final String STATS_PAGE = "/admin/dashboard?days=7";
    private static final String IP_DESTINATION = "an IP address";
    private static final String UNREADABLE_DESTINATION = "an unreadable destination";
    private static final String LOCAL_PROVIDER = "local";

    private final AppProperties appProperties;

    public DiscordMessage message(final List<AdminAlertEvent> alerts, final int dropped) {
        final var lines = alerts.stream().limit(MAX_LINES).map(this::line).toList();
        final var reviewPage = frontendPage(REVIEW_PAGE);
        return DiscordMessage.withoutMentionsOrEmbeds(everyLineThatFits(lines, alerts.size() - lines.size() + dropped,
                more -> "and %d more, see <%s>".formatted(more, reviewPage)));
    }

    public DiscordMessage weeklyDigest(final WeeklyDigest digest) {
        final var head = new ArrayList<String>();
        head.add("**" + WeeklyDigestText.title(digest) + "**");
        head.addAll(WeeklyDigestText.summaryLines(digest, frontendPage(REVIEW_PAGE), DiscordAlertFormatter::linked));
        final var topLinks = WeeklyDigestText.topLinkLines(digest, shortLinkHost(), DiscordAlertFormatter::plain);
        final var statsLine = "Stats (last 7 days): " + linked(frontendPage(STATS_PAGE));
        return DiscordMessage.withoutMentionsOrEmbeds(withinLimit(head, topLinks, 0, more -> "and %d more".formatted(more), List.of(statsLine)));
    }

    private static String everyLineThatFits(final List<String> lines, final int alsoHidden, final IntFunction<String> overflow) {
        final var whole = joined(List.of(), lines, lines.size(), alsoHidden, overflow, List.of());
        if (whole.length() <= MAX_CONTENT_LENGTH) {
            return whole;
        }
        final var room = MAX_CONTENT_LENGTH - overflow.apply(lines.size() + alsoHidden).length();
        final var shown = new ArrayList<String>();
        var used = 0;
        for (final var line : lines) {
            if (used + line.length() + LINE_SEPARATOR.length() <= room) {
                shown.add(line);
                used += line.length() + LINE_SEPARATOR.length();
            }
        }
        shown.add(overflow.apply(lines.size() - shown.size() + alsoHidden));
        return String.join(LINE_SEPARATOR, shown);
    }

    private static String withinLimit(final List<String> head, final List<String> items, final int alsoHidden, final IntFunction<String> overflow,
            final List<String> tail) {
        var shown = items.size();
        var content = joined(head, items, shown, alsoHidden, overflow, tail);
        while (content.length() > MAX_CONTENT_LENGTH && shown > 0) {
            shown--;
            content = joined(head, items, shown, alsoHidden, overflow, tail);
        }
        return content;
    }

    private static String joined(final List<String> head, final List<String> items, final int shown, final int alsoHidden,
            final IntFunction<String> overflow, final List<String> tail) {
        final var lines = new ArrayList<>(head);
        lines.addAll(items.subList(0, shown));
        final var hidden = items.size() - shown + alsoHidden;
        if (hidden > 0) {
            lines.add(overflow.apply(hidden));
        }
        lines.addAll(tail);
        return String.join(LINE_SEPARATOR, lines);
    }

    private String line(final AdminAlertEvent alert) {
        return switch (alert) {
            case ReviewOpened opened -> "New review: %s (%s), %s, <%s>".formatted(
                    link(opened.link()), plain(String.join(", ", opened.reasons())),
                    owner(opened.link()), reviewPage(opened.link()));
            case VisitorReported reported -> "%s: %s (%s), %s, <%s>".formatted(
                    reported.reviewOpened() ? "New review from a visitor report" : "Visitor report on an open review",
                    link(reported.link()), report(reported.category(), reported.details()), owner(reported.link()), reviewPage(reported.link()));
            case LinkBlocked blocked -> "Blocked: %s (%s), %s, <%s>".formatted(
                    link(blocked.link()), blockReason(blocked), owner(blocked.link()), frontendPage(LINKS_PAGE));
            case UserSignedUp signedUp -> "New user: %s signed up with %s, <%s>".formatted(
                    signedUp.userId() == null ? "an account" : "account #" + signedUp.userId(),
                    signUpMethod(signedUp.authProvider()), frontendPage(USERS_PAGE));
            case BlocklistSourceFailed failed -> "%s failed to load: %s%s, <%s>".formatted(
                    plain(failed.sourceName()), plain(failed.error()),
                    failed.fallbackLoaded() ? " (a fallback loaded)" : "", frontendPage(BLOCKLISTS_PAGE));
            case BlocklistSourceRecovered recovered -> "%s loads again".formatted(plain(recovered.sourceName()));
            case BurstDetected burst -> burstLine(burst);
            case BioPageReviewOpened opened -> "New bio page review: %s (%s), %s, <%s>".formatted(
                    bioPage(opened.page()), plain(String.join(", ", opened.reasons())), owner(opened.page().ownerId()),
                    bioPageReviewPage(opened.page()));
            case BioPageReported reported -> "%s: %s (%s), %s, <%s>".formatted(
                    reported.reviewOpened() ? "New bio page review from a visitor report" : "Visitor report on an open bio page review",
                    bioPage(reported.page()), report(reported.category(), reported.details()), owner(reported.page().ownerId()),
                    bioPageReviewPage(reported.page()));
        };
    }

    private String burstLine(final BurstDetected burst) {
        final var readable = DomainNames.toUnicode(burst.domain());
        final var domain = readable.equals(burst.domain()) ? plain(readable) : "%s (%s)".formatted(plain(readable), plain(burst.domain()));
        return ("Possible phishing wave: %d new links to %s in the last %s, from %d different accounts or networks. %s Nothing was blocked. "
                + "Review them: %s").formatted(
                burst.links(), domain, window(burst.window()), burst.creators(), domainAge(burst.domainAgeDays()),
                linked(frontendPage(LINKS_PAGE) + "?search=" + URLEncoder.encode(readable, StandardCharsets.UTF_8)));
    }

    private static String linked(final String url) {
        return "<" + url + ">";
    }

    private String link(final AlertedLink link) {
        return "%s/%s → %s".formatted(shortLinkHost(), plain(link.shortUrl()), destination(link.destinationHost()));
    }

    private static String destination(final String host) {
        if (StringUtils.isBlank(host)) {
            return UNREADABLE_DESTINATION;
        }
        return DomainNames.isIpLiteral(host) ? IP_DESTINATION : plain(host);
    }

    private String bioPage(final AlertedBioPage page) {
        return page.handle() == null
                ? "a bio page without a handle (#%d)".formatted(page.pageId())
                : "%s/@%s".formatted(shortLinkHost(), plain(page.handle()));
    }

    private static String owner(final AlertedLink link) {
        return owner(link.ownerId());
    }

    private static String owner(final Long ownerId) {
        return ownerId == null ? "anonymous" : "account #" + ownerId;
    }

    private static String report(final ReportCategory reportCategory, final String details) {
        final var category = reportCategory == null ? "other" : reportCategory.name().toLowerCase(Locale.ROOT);
        return StringUtils.isBlank(details)
                ? category
                : "%s: \"%s\"".formatted(category, plain(details));
    }

    private static String blockReason(final LinkBlocked blocked) {
        final var detail = plain(blocked.detail());
        final var reason = Objects.requireNonNullElse(blocked.reason(), BlockReason.ADMIN);
        final var text = switch (reason) {
            case SAFE_BROWSING -> "Safe Browsing";
            case BLOCKLIST -> "on";
            case DOMAIN_RULE -> "domain rule";
            case SCAM_SIGNALS -> "scam signals:";
            case ADMIN -> "by an admin:";
            case USER_BLOCKED -> "account blocked";
        };
        if (detail.isEmpty()) {
            return StringUtils.removeEnd(text, ":");
        }
        return reason == BlockReason.SAFE_BROWSING ? text + ": " + detail : text + " " + detail;
    }

    private static String window(final Duration window) {
        if (window.toHours() > 0 && window.equals(Duration.ofHours(window.toHours()))) {
            return lastUnits(window.toHours(), "hour");
        }
        if (window.toMinutes() > 0 && window.equals(Duration.ofMinutes(window.toMinutes()))) {
            return lastUnits(window.toMinutes(), "minute");
        }
        return lastUnits(window.toSeconds(), "second");
    }

    private static String lastUnits(final long amount, final String unit) {
        return amount == 1 ? unit : count(amount, unit);
    }

    private static String domainAge(final Integer ageDays) {
        if (ageDays == null) {
            return "The domain's age is unknown.";
        }
        return ageDays == 0 ? "The domain was registered today." : "The domain was registered %s ago.".formatted(count(ageDays, "day"));
    }

    private static String count(final long amount, final String unit) {
        return amount == 1 ? "1 " + unit : amount + " " + unit + "s";
    }

    private static String signUpMethod(final String authProvider) {
        final var provider = StringUtils.defaultIfBlank(authProvider, LOCAL_PROVIDER).toLowerCase(Locale.ROOT);
        return switch (provider) {
            case LOCAL_PROVIDER -> "email";
            case "google" -> "Google";
            case "github" -> "GitHub";
            default -> plain(provider);
        };
    }

    private String reviewPage(final AlertedLink link) {
        return frontendPage(REVIEW_PAGE) + "?entry=" + link.urlId();
    }

    private String bioPageReviewPage(final AlertedBioPage page) {
        return frontendPage(REVIEW_PAGE) + "?bio=" + page.pageId();
    }

    private String frontendPage(final String path) {
        return StringUtils.removeEnd(StringUtils.defaultString(appProperties.getFrontendUrl()), "/") + path;
    }

    private String shortLinkHost() {
        final var serverUrl = StringUtils.removeEnd(StringUtils.defaultString(appProperties.getServerUrl()), "/");
        return serverUrl.contains("://") ? StringUtils.substringAfter(serverUrl, "://") : serverUrl;
    }

    private static String plain(final String text) {
        return DiscordText.plain(text);
    }

}
