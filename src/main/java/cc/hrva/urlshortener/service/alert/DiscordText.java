package cc.hrva.urlshortener.service.alert;

import cc.hrva.urlshortener.util.DomainNames;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public final class DiscordText {

    private static final String EMAIL_REMOVED = "(email removed)";
    private static final String IP_REMOVED = "(IP removed)";
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    private static final Pattern MASKED_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)]*)\\)");
    private static final Pattern FORMATTING = Pattern.compile("[`<>\\[\\]]");
    private static final Pattern EMAIL = Pattern.compile("[\\w.%+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]*:[0-9A-Fa-f]*:[0-9A-Fa-f:.]*");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])\\d{1,3}(?:\\.\\d{1,3}){3}(?!\\.?\\d)");
    private static final Pattern WEB_ADDRESS = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\"']+");
    private static final Pattern MARKDOWN = Pattern.compile("[\\\\*_~|]");

    private DiscordText() {
    }

    public static String plain(final String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        return escaped(cleaned(text));
    }

    private static String cleaned(final String text) {
        var cleaned = INVISIBLE.matcher(text).replaceAll(" ");
        cleaned = MASKED_LINK.matcher(cleaned).replaceAll("$1 $2");
        cleaned = EMAIL.matcher(cleaned).replaceAll(EMAIL_REMOVED);
        cleaned = IPV6.matcher(cleaned).replaceAll(DiscordText::withoutIpAddress);
        cleaned = IPV4.matcher(cleaned).replaceAll(DiscordText::withoutIpAddress);
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
