package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.util.DomainNames;
import com.google.common.net.InternetDomainName;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
    private static final String ACE_PREFIX = "xn--";
    private static final String LOCALHOST = "localhost";
    private static final List<String> SUFFIX_PREFIXES = List.of("*.", ".");
    private static final int ASCII_LIMIT = 0x80;
    private static final Set<Integer> CONTEXTUAL_CODE_POINTS = Set.of((int) '-', 0x200C, 0x200D, 0xB7, 0x375, 0x5F3, 0x5F4, 0x30FB);

    private final AppProperties appProperties;

    @PostConstruct
    public void checkSettings() {
        final var domains = appProperties.getDomains();
        requireEvery("app.domains.reserved", domains.getReserved(), entry -> reservedKey(entry).isPresent());
        requireEvery("app.domains.primary-hosts", domains.getPrimaryHosts(), CustomDomainHostnames::isPrimaryHostEntry);
        requireEvery("app.domains.ignored-hostnames", domains.getIgnoredHostnames(), entry -> wellFormedKey(entry).isPresent());
        if (wellFormedKey(domains.getCnameTarget()).isEmpty()) {
            throw unreadable("app.domains.cname-target", domains.getCnameTarget());
        }
    }

    public static Optional<String> hostnameKey(final String host) {
        if (StringUtils.isBlank(host)) {
            return Optional.empty();
        }
        try {
            return Optional.of(StringUtils.removeEnd(DomainNames.toAscii(host.trim().toLowerCase(Locale.ROOT)), "."))
                    .filter(StringUtils::isNotEmpty);
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
        if (!hasValidLabels(hostname)) {
            throw HostnameRefusal.BAD_SHAPE.toException();
        }
        if (isLocalhost(hostname) || !isUnderPublicSuffix(hostname)) {
            throw HostnameRefusal.NOT_OWNABLE.toException();
        }
        if (isReserved(hostname) || isPrimary(hostname) || isCnameTarget(hostname)) {
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
                        .map(CustomDomainHostnames::reservedKey)
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

    private boolean isCnameTarget(final String hostname) {
        return hostnameKey(appProperties.getDomains().getCnameTarget()).filter(hostname::equals).isPresent();
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

    private static void requireEvery(final String property, final List<String> entries, final Predicate<String> readable) {
        entries.stream()
                .filter(StringUtils::isNotBlank)
                .filter(readable.negate())
                .findFirst()
                .ifPresent(entry -> {
                    throw unreadable(property, entry);
                });
    }

    private static IllegalStateException unreadable(final String property, final String entry) {
        return new IllegalStateException("%s has an entry that isn't a hostname: '%s'".formatted(property, entry));
    }

    private static Optional<String> reservedKey(final String entry) {
        final var trimmed = StringUtils.trimToEmpty(entry);
        final var withoutSuffixPrefix = SUFFIX_PREFIXES.stream()
                .filter(trimmed::startsWith)
                .findFirst()
                .map(prefix -> trimmed.substring(prefix.length()))
                .orElse(trimmed);
        return wellFormedKey(withoutSuffixPrefix);
    }

    private static Optional<String> wellFormedKey(final String entry) {
        return hostnameKey(entry).filter(CustomDomainHostnames::hasValidLabels);
    }

    private static boolean isPrimaryHostEntry(final String entry) {
        return withoutPort(entry).filter(host -> DomainNames.isIpLiteral(host) || hasValidLabels(host)).isPresent();
    }

    private static boolean hasValidLabels(final String hostname) {
        return Arrays.stream(hostname.split("\\.", -1)).allMatch(CustomDomainHostnames::isValidLabel);
    }

    private static boolean isValidLabel(final String label) {
        return LABEL.matcher(label).matches() && (!label.startsWith(ACE_PREFIX) || isRealPunycode(label));
    }

    private static boolean isRealPunycode(final String label) {
        return Punycode.decode(label.substring(ACE_PREFIX.length()))
                .filter(decoded -> decoded.codePoints().anyMatch(codePoint -> codePoint >= ASCII_LIMIT))
                .filter(decoded -> decoded.codePoints().allMatch(CustomDomainHostnames::isLabelCodePoint))
                .flatMap(Punycode::encode)
                .filter(encoded -> (ACE_PREFIX + encoded).equals(label))
                .isPresent();
    }

    private static boolean isLabelCodePoint(final int codePoint) {
        final var type = Character.getType(codePoint);
        return Character.isLetter(codePoint) && !Character.isUpperCase(codePoint) && !Character.isTitleCase(codePoint)
                || Character.isDigit(codePoint)
                || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK
                || CONTEXTUAL_CODE_POINTS.contains(codePoint);
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
