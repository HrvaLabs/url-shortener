package cc.hrva.urlshortener.util;

import java.net.IDN;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.commons.lang3.StringUtils;

public final class DomainNames {

    private static final Set<String> SECOND_LEVEL_LABELS = Set.of("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go");

    private DomainNames() {}

    public static Optional<String> hostOf(final String url) {
        if (StringUtils.isBlank(url)) {
            return Optional.empty();
        }
        try {
            final var withScheme = url.contains("://") ? url : "https://" + url;
            return Optional.ofNullable(URI.create(withScheme).getAuthority())
                    .map(DomainNames::hostOfAuthority)
                    .filter(StringUtils::isNotEmpty);
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static List<String> candidates(final String host) {
        final var labels = host.split("\\.");
        return IntStream.range(0, labels.length - 1)
                .mapToObj(start -> String.join(".", Arrays.copyOfRange(labels, start, labels.length)))
                .toList();
    }

    public static String registrableDomain(final String host) {
        final var labels = host.split("\\.");
        final var count = labels.length;
        if (count >= 3 && labels[count - 1].length() == 2 && SECOND_LEVEL_LABELS.contains(labels[count - 2])) {
            return String.join(".", labels[count - 3], labels[count - 2], labels[count - 1]);
        }
        return count >= 2 ? labels[count - 2] + "." + labels[count - 1] : labels[0];
    }

    private static String hostOfAuthority(final String authority) {
        final var hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
        if (hostAndPort.startsWith("[")) {
            return StringUtils.lowerCase(StringUtils.substringBetween(hostAndPort, "[", "]"), Locale.ROOT);
        }
        final var host = StringUtils.removeEnd(StringUtils.substringBeforeLast(hostAndPort, ":"), ".");
        return IDN.toASCII(host).toLowerCase(Locale.ROOT);
    }

}
