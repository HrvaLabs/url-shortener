package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.util.DomainNames;
import com.google.common.net.InternetDomainName;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomDomainHostnames {

    private static final int MAX_LENGTH = 253;
    private static final Pattern LABEL = Pattern.compile("^(?!-)[a-z0-9-]{1,63}(?<!-)$");
    private static final Pattern PORT = Pattern.compile(":\\d*$");
    private static final String LOCALHOST = "localhost";

    private final AppProperties appProperties;

    public static Optional<String> hostnameKey(final String host) {
        if (StringUtils.isBlank(host)) {
            return Optional.empty();
        }
        final var stripped = StringUtils.removeEnd(host.trim().toLowerCase(Locale.ROOT), ".");
        try {
            return Optional.of(DomainNames.toAscii(stripped)).filter(StringUtils::isNotEmpty);
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public String normalize(final String input) {
        if (StringUtils.isBlank(input)) {
            throw HostnameRefusal.BLANK.toException();
        }
        final var hostname = hostnameKey(input).orElseThrow(HostnameRefusal.BAD_SHAPE::toException);
        if (DomainNames.isIpLiteral(hostname)) {
            throw HostnameRefusal.NOT_OWNABLE.toException();
        }
        if (hostname.length() > MAX_LENGTH) {
            throw HostnameRefusal.TOO_LONG.toException();
        }
        if (!Arrays.stream(hostname.split("\\.", -1)).allMatch(label -> LABEL.matcher(label).matches())) {
            throw HostnameRefusal.BAD_SHAPE.toException();
        }
        if (isLocalhost(hostname) || !isUnderPublicSuffix(hostname)) {
            throw HostnameRefusal.NOT_OWNABLE.toException();
        }
        if (isReserved(hostname)) {
            throw HostnameRefusal.RESERVED.toException();
        }
        if (DomainNames.registrableDomain(hostname).equals(hostname)) {
            throw HostnameRefusal.APEX.toException();
        }
        return hostname;
    }

    public boolean isReserved(final String hostname) {
        return hostnameKey(hostname)
                .map(key -> appProperties.getDomains().getReserved().stream()
                        .map(CustomDomainHostnames::hostnameKey)
                        .flatMap(Optional::stream)
                        .anyMatch(reserved -> key.equals(reserved) || key.endsWith("." + reserved)))
                .orElse(false);
    }

    public boolean isPrimary(final String host) {
        return withoutPort(host)
                .map(key -> Stream.concat(serverUrlHost().stream(), appProperties.getDomains().getPrimaryHosts().stream())
                        .map(CustomDomainHostnames::withoutPort)
                        .flatMap(Optional::stream)
                        .anyMatch(key::equals))
                .orElse(false);
    }

    private Optional<String> serverUrlHost() {
        final var serverUrl = appProperties.getServerUrl();
        if (StringUtils.isBlank(serverUrl)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(URI.create(serverUrl.trim()).getHost());
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> withoutPort(final String host) {
        if (StringUtils.isBlank(host)) {
            return Optional.empty();
        }
        final var trimmed = host.trim();
        if (trimmed.startsWith("[")) {
            return Optional.ofNullable(StringUtils.substringBetween(trimmed, "[", "]"))
                    .filter(StringUtils::isNotBlank)
                    .map(address -> address.toLowerCase(Locale.ROOT));
        }
        final var hostOnly = StringUtils.countMatches(trimmed, ':') == 1 ? PORT.matcher(trimmed).replaceFirst("") : trimmed;
        return hostnameKey(hostOnly);
    }

    private static boolean isLocalhost(final String hostname) {
        return LOCALHOST.equals(hostname) || hostname.endsWith("." + LOCALHOST);
    }

    private static boolean isUnderPublicSuffix(final String hostname) {
        try {
            return InternetDomainName.from(hostname).isUnderPublicSuffix();
        } catch (final IllegalArgumentException exception) {
            return false;
        }
    }

}
