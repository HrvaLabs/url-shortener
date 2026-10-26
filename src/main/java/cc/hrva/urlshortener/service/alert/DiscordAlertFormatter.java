package cc.hrva.urlshortener.service.alert;

import cc.hrva.urlshortener.client.DiscordMessage;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BlocklistSourceFailed;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BlocklistSourceRecovered;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.LinkBlocked;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.ReviewOpened;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.UserSignedUp;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.VisitorReported;
import cc.hrva.urlshortener.service.digest.WeeklyDigestText;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DiscordAlertFormatter {

    private static final int MAX_LINES = 10;
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int OVERFLOW_ROOM = 120;
    private static final int MAX_SHORT_URL_LENGTH = 40;
    private static final int MAX_HOST_LENGTH = 100;
    private static final int MAX_REASONS_LENGTH = 200;
    private static final int MAX_DETAILS_LENGTH = 200;
    private static final int MAX_BLOCK_DETAIL_LENGTH = 120;
    private static final int MAX_SOURCE_NAME_LENGTH = 80;
    private static final int MAX_ERROR_LENGTH = 150;
    private static final int MAX_PROVIDER_LENGTH = 30;
    private static final int MAX_INPUT_LENGTH = 2000;
    private static final String LINE_SEPARATOR = "\n";
    private static final String ELLIPSIS = "…";
    private static final String REVIEW_PAGE = "/admin/review";
    private static final String LINKS_PAGE = "/admin/urls";
    private static final String USERS_PAGE = "/admin";
    private static final String BLOCKLISTS_PAGE = "/admin/blocklists";
    private static final String STATS_PAGE = "/admin/dashboard?days=7";
    private static final String EMAIL_REMOVED = "(email removed)";
    private static final String IP_REMOVED = "(IP removed)";
    private static final String IP_DESTINATION = "an IP address";
    private static final String UNREADABLE_DESTINATION = "an unreadable destination";
    private static final String LOCAL_PROVIDER = "local";
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    private static final Pattern MASKED_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)]*)\\)");
    private static final Pattern FORMATTING = Pattern.compile("[`<>\\[\\]]");
    private static final Pattern EMAIL = Pattern.compile("[\\w.%+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]*:[0-9A-Fa-f]*:[0-9A-Fa-f:.]*");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])\\d{1,3}(?:\\.\\d{1,3}){3}(?!\\.?\\d)");
    private static final Pattern WEB_ADDRESS = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\"']+");
    private static final Pattern MARKDOWN = Pattern.compile("[\\\\*_~|]");

    private final AppProperties appProperties;

    public DiscordMessage message(final List<AdminAlertEvent> alerts, final int dropped) {
        final var shown = new ArrayList<String>();
        var length = 0;
        for (final var alert : alerts) {
            final var line = line(alert);
            final var lengthWithLine = length + (shown.isEmpty() ? 0 : LINE_SEPARATOR.length()) + line.length();
            if (shown.size() == MAX_LINES || lengthWithLine > MAX_CONTENT_LENGTH - OVERFLOW_ROOM) {
                break;
            }
            shown.add(line);
            length = lengthWithLine;
        }
        final var more = alerts.size() - shown.size() + dropped;
        if (more > 0) {
            shown.add("%sand %d more, see <%s>".formatted(ELLIPSIS, more, frontendPage(REVIEW_PAGE)));
        }
        return DiscordMessage.withoutMentionsOrEmbeds(String.join(LINE_SEPARATOR, shown));
    }

    public DiscordMessage weeklyDigest(final WeeklyDigest digest) {
        final var head = new ArrayList<String>();
        head.add("**" + WeeklyDigestText.title(digest) + "**");
        head.addAll(WeeklyDigestText.summaryLines(digest));
        final var topLinks = WeeklyDigestText.topLinkLines(digest, shortLinkHost(), DiscordAlertFormatter::plainInFull);
        final var statsLine = "Stats (last 7 days): <%s>".formatted(frontendPage(STATS_PAGE));

        var shown = topLinks.size();
        var content = weeklyDigestContent(head, topLinks, shown, statsLine);
        while (content.length() > MAX_CONTENT_LENGTH && shown > 0) {
            shown--;
            content = weeklyDigestContent(head, topLinks, shown, statsLine);
        }
        return DiscordMessage.withoutMentionsOrEmbeds(content);
    }

    private static String weeklyDigestContent(final List<String> head, final List<String> topLinks, final int shown, final String statsLine) {
        final var lines = new ArrayList<>(head);
        lines.addAll(topLinks.subList(0, shown));
        if (shown < topLinks.size()) {
            lines.add("and %d more".formatted(topLinks.size() - shown));
        }
        lines.add(statsLine);
        return String.join(LINE_SEPARATOR, lines);
    }

    private String line(final AdminAlertEvent alert) {
        return switch (alert) {
            case ReviewOpened opened -> "New review: %s (%s), %s, <%s>".formatted(
                    link(opened.link()), plain(String.join(", ", opened.reasons()), MAX_REASONS_LENGTH),
                    owner(opened.link()), reviewPage(opened.link()));
            case VisitorReported reported -> "%s: %s (%s), %s, <%s>".formatted(
                    reported.reviewOpened() ? "New review from a visitor report" : "Visitor report on an open review",
                    link(reported.link()), report(reported), owner(reported.link()), reviewPage(reported.link()));
            case LinkBlocked blocked -> "Blocked: %s (%s), %s, <%s>".formatted(
                    link(blocked.link()), blockReason(blocked), owner(blocked.link()), frontendPage(LINKS_PAGE));
            case UserSignedUp signedUp -> "New user: %s signed up with %s, <%s>".formatted(
                    signedUp.userId() == null ? "an account" : "account #" + signedUp.userId(),
                    signUpMethod(signedUp.authProvider()), frontendPage(USERS_PAGE));
            case BlocklistSourceFailed failed -> "%s failed to load: %s%s, <%s>".formatted(
                    plain(failed.sourceName(), MAX_SOURCE_NAME_LENGTH), plain(failed.error(), MAX_ERROR_LENGTH),
                    failed.fallbackLoaded() ? " (a fallback loaded)" : "", frontendPage(BLOCKLISTS_PAGE));
            case BlocklistSourceRecovered recovered -> "%s loads again".formatted(plain(recovered.sourceName(), MAX_SOURCE_NAME_LENGTH));
        };
    }

    private String link(final AlertedLink link) {
        return "%s/%s → %s".formatted(shortLinkHost(), plain(link.shortUrl(), MAX_SHORT_URL_LENGTH), destination(link.destinationHost()));
    }

    private static String destination(final String host) {
        if (StringUtils.isBlank(host)) {
            return UNREADABLE_DESTINATION;
        }
        return DomainNames.isIpLiteral(host) ? IP_DESTINATION : plain(host, MAX_HOST_LENGTH);
    }

    private static String owner(final AlertedLink link) {
        return link.ownerId() == null ? "anonymous" : "account #" + link.ownerId();
    }

    private static String report(final VisitorReported reported) {
        final var category = reported.category() == null ? "other" : reported.category().name().toLowerCase(Locale.ROOT);
        return StringUtils.isBlank(reported.details())
                ? category
                : "%s: \"%s\"".formatted(category, plain(reported.details(), MAX_DETAILS_LENGTH));
    }

    private static String blockReason(final LinkBlocked blocked) {
        final var detail = plain(blocked.detail(), MAX_BLOCK_DETAIL_LENGTH);
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

    private static String signUpMethod(final String authProvider) {
        final var provider = StringUtils.defaultIfBlank(authProvider, LOCAL_PROVIDER).toLowerCase(Locale.ROOT);
        return switch (provider) {
            case LOCAL_PROVIDER -> "email";
            case "google" -> "Google";
            case "github" -> "GitHub";
            default -> plain(provider, MAX_PROVIDER_LENGTH);
        };
    }

    private String reviewPage(final AlertedLink link) {
        return frontendPage(REVIEW_PAGE) + "?entry=" + link.urlId();
    }

    private String frontendPage(final String path) {
        return StringUtils.removeEnd(StringUtils.defaultString(appProperties.getFrontendUrl()), "/") + path;
    }

    private String shortLinkHost() {
        final var serverUrl = StringUtils.removeEnd(StringUtils.defaultString(appProperties.getServerUrl()), "/");
        return serverUrl.contains("://") ? StringUtils.substringAfter(serverUrl, "://") : serverUrl;
    }

    private static String plain(final String text, final int maxLength) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        return escaped(StringUtils.abbreviate(cleaned(StringUtils.left(text, MAX_INPUT_LENGTH)), ELLIPSIS, maxLength));
    }

    private static String plainInFull(final String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        return escaped(cleaned(text));
    }

    private static String cleaned(final String text) {
        var cleaned = INVISIBLE.matcher(text).replaceAll(" ");
        cleaned = MASKED_LINK.matcher(cleaned).replaceAll("$1 $2");
        cleaned = EMAIL.matcher(cleaned).replaceAll(EMAIL_REMOVED);
        cleaned = IPV6.matcher(cleaned).replaceAll(DiscordAlertFormatter::withoutIpAddress);
        cleaned = IPV4.matcher(cleaned).replaceAll(DiscordAlertFormatter::withoutIpAddress);
        cleaned = FORMATTING.matcher(cleaned).replaceAll("");
        return StringUtils.normalizeSpace(cleaned);
    }

    private static String withoutIpAddress(final MatchResult match) {
        var candidate = match.group();
        while (!candidate.isEmpty()) {
            if (DomainNames.isIpLiteral(candidate)) {
                return Matcher.quoteReplacement(IP_REMOVED + match.group().substring(candidate.length()));
            }
            if (!StringUtils.endsWithAny(candidate, ".", ":")) {
                break;
            }
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return Matcher.quoteReplacement(match.group());
    }

    private static String escaped(final String text) {
        final var result = new StringBuilder();
        final var addresses = WEB_ADDRESS.matcher(text);
        var start = 0;
        while (addresses.find()) {
            result.append(MARKDOWN.matcher(text.substring(start, addresses.start())).replaceAll("\\\\$0"));
            result.append('<').append(addresses.group()).append('>');
            start = addresses.end();
        }
        result.append(MARKDOWN.matcher(text.substring(start)).replaceAll("\\\\$0"));
        return result.toString();
    }

}
