package cc.hrva.urlshortener.util;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public final class LinkKeys {

    private static final Pattern LINK = Pattern.compile(
            "^(?<scheme>https?)://(?:[^/?#\\s]*@)?(?<host>\\[[^\\]/?#\\s]*\\]|[^/?#\\s:@\\[\\]]+)(?::(?<port>\\d{0,5}))?"
                    + "(?<path>/[^?#]*)?(?:\\?(?<query>[^#]*))?(?:#.*)?$",
            Pattern.CASE_INSENSITIVE);

    private LinkKeys() {}

    public static List<String> of(final String url) {
        if (url == null) {
            return List.of();
        }
        final var matcher = LINK.matcher(url.strip());
        if (!matcher.matches()) {
            return List.of();
        }
        final var scheme = matcher.group("scheme").toLowerCase(Locale.ROOT);
        return DomainNames.hostOf("https://" + matcher.group("host"))
                .map(host -> keysOf(scheme, host, matcher.group("port"), matcher.group("path"), matcher.group("query")))
                .orElse(List.of());
    }

    private static List<String> keysOf(
            final String scheme, final String host, final String port, final String path, final String query) {
        final var withoutQuery = (host.contains(":") ? "[" + host + "]" : host)
                + portOf(scheme, port)
                + RedirectLocations.percentEncoded(StringUtils.defaultIfEmpty(path, "/"));
        return StringUtils.isEmpty(query)
                ? List.of(withoutQuery)
                : List.of(withoutQuery + "?" + RedirectLocations.percentEncoded(query), withoutQuery);
    }

    private static String portOf(final String scheme, final String port) {
        if (StringUtils.isEmpty(port)) {
            return "";
        }
        final var number = Integer.parseInt(port);
        final var defaultPort = scheme.equals("https") ? 443 : 80;
        return number == defaultPort ? "" : ":" + number;
    }

}
