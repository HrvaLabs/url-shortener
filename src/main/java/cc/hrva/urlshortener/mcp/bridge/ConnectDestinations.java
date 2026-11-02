package cc.hrva.urlshortener.mcp.bridge;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class ConnectDestinations {

    private static final Set<String> WEB_SCHEMES = Set.of("http", "https");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final String LOCALHOST_SUFFIX = ".localhost";
    private static final String LOOPBACK_V4_PREFIX = "127.";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_LETTERS_OR_DIGITS = Pattern.compile("[^a-z0-9]+");
    private static final String WORD_EDGE = " ";

    private final List<AppProperties.KnownApp> knownApps;

    public ConnectDestinations(final AppProperties appProperties) {
        this.knownApps = appProperties.getMcp().getKnownApps();
    }

    public ConnectRequestView viewOf(final String clientName, final String redirectUri) {
        final var uri = parsed(redirectUri);
        final var scheme = StringUtils.lowerCase(uri == null ? null : uri.getScheme(), Locale.ROOT);
        if (StringUtils.isBlank(scheme)) {
            return unknown(clientName);
        }
        if (!WEB_SCHEMES.contains(scheme)) {
            return new ConnectRequestView(clientName, null, false, false, scheme, false);
        }
        if (StringUtils.isBlank(uri.getHost())) {
            return unknown(clientName);
        }
        final var host = uri.getHost().toLowerCase(Locale.ROOT);
        final var loopback = isLoopback(host);
        return new ConnectRequestView(clientName, host, loopback, !loopback && claimsAnotherApp(clientName, host), null, false);
    }

    private static ConnectRequestView unknown(final String clientName) {
        return new ConnectRequestView(clientName, null, false, false, null, true);
    }

    private boolean claimsAnotherApp(final String clientName, final String host) {
        final var name = WORD_EDGE + words(clientName) + WORD_EDGE;
        return knownApps.stream()
                .filter(app -> app.getNames().stream().anyMatch(known -> name.contains(WORD_EDGE + words(known) + WORD_EDGE)))
                .anyMatch(app -> app.getDomains().stream().noneMatch(domain -> isOnDomain(host, domain)));
    }

    private static boolean isOnDomain(final String host, final String domain) {
        final var ownDomain = domain.toLowerCase(Locale.ROOT);
        return host.equals(ownDomain) || host.endsWith("." + ownDomain);
    }

    private static boolean isLoopback(final String host) {
        return LOOPBACK_HOSTS.contains(host) || host.endsWith(LOCALHOST_SUFFIX) || host.startsWith(LOOPBACK_V4_PREFIX) && host.chars().allMatch(c -> c == '.' || Character.isDigit(c));
    }

    private static String words(final String text) {
        final var withoutMarks = MARKS.matcher(Normalizer.normalize(StringUtils.defaultString(text), Normalizer.Form.NFKD)).replaceAll("");
        return NOT_LETTERS_OR_DIGITS.matcher(withoutMarks.toLowerCase(Locale.ROOT)).replaceAll(WORD_EDGE).strip();
    }

    private static URI parsed(final String redirectUri) {
        try {
            return redirectUri == null ? null : new URI(redirectUri);
        } catch (final URISyntaxException exception) {
            return null;
        }
    }

}
