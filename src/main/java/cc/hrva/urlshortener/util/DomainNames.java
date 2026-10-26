package cc.hrva.urlshortener.util;

import com.google.common.net.InternetDomainName;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.apache.commons.lang3.StringUtils;

public final class DomainNames {

    public static final Pattern HOST_NAME = Pattern.compile(
            "^(?!-)[a-z0-9_-]{1,63}(?<!-)(?:\\.(?!-)[a-z0-9_-]{1,63}(?<!-))+\\.?$", Pattern.CASE_INSENSITIVE);

    private static final String HEX_PREFIX = "0x";
    private static final int MAX_IPV4_PARTS = 4;
    private static final int BYTE_VALUES = 256;

    private DomainNames() {}

    public static Optional<String> hostOf(final String url) {
        return hostNameOf(url).map(HostName::ascii);
    }

    public static Optional<HostName> hostNameOf(final String url) {
        if (StringUtils.isBlank(url)) {
            return Optional.empty();
        }
        try {
            final var withScheme = url.contains("://") ? url : "https://" + url;
            return Optional.ofNullable(URI.create(withScheme).getAuthority())
                    .map(DomainNames::hostNameOfAuthority)
                    .filter(host -> StringUtils.isNotEmpty(host.ascii()));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static String toAscii(final String host) {
        final var ascii = IDN.toASCII(host.toLowerCase(Locale.ROOT), IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        return ipv4Address(ascii).orElse(ascii);
    }

    public static String toUnicode(final String host) {
        return IDN.toUnicode(toAscii(host), IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
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

    public static boolean isPublicSuffix(final String host) {
        return internetDomainName(host).filter(InternetDomainName::isPublicSuffix).isPresent();
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

    private static Optional<String> ipv4Address(final String host) {
        final var parts = new ArrayList<>(Arrays.asList(host.split("\\.", -1)));
        if (parts.size() > 1 && parts.getLast().isEmpty()) {
            parts.removeLast();
        }
        if (parts.size() > MAX_IPV4_PARTS || ipv4Number(parts.getLast()).isEmpty()) {
            return Optional.empty();
        }
        final var numbers = parts.stream().map(DomainNames::ipv4Number).toList();
        if (numbers.stream().anyMatch(OptionalLong::isEmpty)) {
            return Optional.empty();
        }
        final var leading = numbers.subList(0, numbers.size() - 1).stream().mapToLong(OptionalLong::getAsLong).toArray();
        final var last = numbers.getLast().getAsLong();
        final var lastLimit = (long) Math.pow(BYTE_VALUES, MAX_IPV4_PARTS + 1 - numbers.size());
        if (Arrays.stream(leading).anyMatch(number -> number >= BYTE_VALUES) || last >= lastLimit) {
            return Optional.empty();
        }
        var address = last;
        for (int index = 0; index < leading.length; index++) {
            address += leading[index] << 8 * (MAX_IPV4_PARTS - 1 - index);
        }
        return Optional.of("%d.%d.%d.%d".formatted(address >>> 24, address >>> 16 & 0xFF, address >>> 8 & 0xFF, address & 0xFF));
    }

    private static OptionalLong ipv4Number(final String part) {
        final var hex = part.startsWith(HEX_PREFIX);
        final var octal = !hex && part.length() > 1 && part.startsWith("0");
        final var radix = hex ? 16 : octal ? 8 : 10;
        final var digits = hex ? part.substring(HEX_PREFIX.length()) : octal ? part.substring(1) : part;
        if (part.isEmpty() || !digits.chars().allMatch(character -> character < 0x80 && Character.digit(character, radix) >= 0)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(digits.isEmpty() ? 0 : Long.parseLong(digits, radix));
        } catch (final NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }

    private static HostName hostNameOfAuthority(final String authority) {
        final var hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
        if (hostAndPort.startsWith("[")) {
            final var address = StringUtils.lowerCase(StringUtils.substringBetween(hostAndPort, "[", "]"), Locale.ROOT);
            return new HostName(address, address);
        }
        final var ascii = StringUtils.removeEnd(toAscii(StringUtils.substringBeforeLast(hostAndPort, ":")), ".");
        return new HostName(ascii, toUnicode(ascii));
    }

}
