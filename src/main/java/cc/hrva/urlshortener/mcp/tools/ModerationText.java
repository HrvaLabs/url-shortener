package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.util.EmailAddresses;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

final class ModerationText {

    private static final int MAX_PATH_LENGTH = 80;
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");
    private static final Pattern NOT_PLAIN_TEXT = Pattern.compile("[<>\\s\\p{Cntrl}]", Pattern.UNICODE_CHARACTER_CLASS);

    private ModerationText() {}

    static String masked(final User user) {
        return EmailAddresses.masked(user.getEmail()) + " #" + user.getId();
    }

    static String destination(final String longUrl) {
        final var withoutScheme = SCHEME.matcher(StringUtils.trimToEmpty(longUrl)).replaceFirst("");
        final var beforeQuery = StringUtils.substringBefore(StringUtils.substringBefore(withoutScheme, "#"), "?");
        final var pathStart = beforeQuery.indexOf('/');
        final var authority = pathStart < 0 ? beforeQuery : beforeQuery.substring(0, pathStart);
        final var host = DomainNames.hostOf(authority).orElse("unknown host");
        final var path = pathStart < 0 ? "" : StringUtils.abbreviate(plainText(beforeQuery.substring(pathStart)), MAX_PATH_LENGTH);
        return "<" + host + path + ">";
    }

    static String delimited(final String text) {
        return "<" + plainText(text) + ">";
    }

    static String counted(final long count, final String one, final String many) {
        return "%d %s".formatted(count, count == 1 ? one : many);
    }

    static int pageNumber(final Integer page) {
        return Math.max(0, Objects.requireNonNullElse(page, 0));
    }

    private static String plainText(final String path) {
        return NOT_PLAIN_TEXT.matcher(path).replaceAll(character -> "%%%02X".formatted((int) character.group().charAt(0)));
    }

}
