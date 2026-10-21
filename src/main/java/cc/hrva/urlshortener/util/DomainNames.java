package cc.hrva.urlshortener.util;

import com.google.common.net.InternetDomainName;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.apache.commons.lang3.StringUtils;

public final class DomainNames {

    public static final Pattern HOST_NAME = Pattern.compile(
            "^(?!-)[a-z0-9_-]+(?<!-)(?:\\.(?!-)[a-z0-9_-]+(?<!-))+\\.?$", Pattern.CASE_INSENSITIVE);

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
        return internetDomainName(host)
                .filter(InternetDomainName::isUnderPublicSuffix)
                .map(name -> name.topPrivateDomain().toString())
                .orElse(host);
    }

    public static String registryDomain(final String host) {
        return internetDomainName(host)
                .filter(InternetDomainName::isUnderRegistrySuffix)
                .map(name -> name.topDomainUnderRegistrySuffix().toString())
                .orElse(host);
    }

    public static boolean isUnderRegistrySuffix(final String host) {
        return internetDomainName(host).filter(InternetDomainName::isUnderRegistrySuffix).isPresent();
    }

    public static boolean isIpLiteral(final String host) {
        try {
            InetAddress.ofLiteral(host);
            return true;
        } catch (final IllegalArgumentException exception) {
            return false;
        }
    }

    private static Optional<InternetDomainName> internetDomainName(final String host) {
        if (host == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(InternetDomainName.from(host));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
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
