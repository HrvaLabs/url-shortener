package cc.hrva.urlshortener.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

public final class ForwardedQueries {

    private static final int MAX_FORWARDED_LENGTH = 2048;
    private static final String QR_MARKER = "qr";
    private static final String PAIR_SEPARATOR = "&";
    private static final char QUERY_START = '?';
    private static final char FRAGMENT_START = '#';
    private static final char NAME_END = '=';
    private static final int MAX_DECODE_ROUNDS = 3;
    private static final Pattern LINK_SCHEME = Pattern.compile("^(https?):", Pattern.CASE_INSENSITIVE);
    private static final Pattern IGNORED_IN_LINKS = Pattern.compile("[\\t\\n\\r]");

    private ForwardedQueries() {}

    public static String appendTo(final String destination, final String query) {
        if (StringUtils.isEmpty(query)) {
            return destination;
        }
        final var fragmentStart = destination.indexOf(FRAGMENT_START);
        final var beforeFragment = fragmentStart < 0 ? destination : destination.substring(0, fragmentStart);
        final var fragment = fragmentStart < 0 ? "" : destination.substring(fragmentStart);
        final var queryStart = beforeFragment.indexOf(QUERY_START);
        final var forwarded = forwardedPairs(destination, query)
                .map(pair -> pair.replace(String.valueOf(FRAGMENT_START), "%23"))
                .collect(Collectors.joining(PAIR_SEPARATOR));
        if (forwarded.isEmpty() || forwarded.length() > MAX_FORWARDED_LENGTH) {
            return destination;
        }
        return beforeFragment + separatorAfter(beforeFragment, queryStart) + forwarded + fragment;
    }

    public static List<String> linksIn(final String destination, final String query) {
        if (StringUtils.isEmpty(query)) {
            return List.of();
        }
        return forwardedPairs(destination, query)
                .map(ForwardedQueries::valueOf)
                .flatMap(ForwardedQueries::linksOf)
                .distinct()
                .toList();
    }

    private static Stream<String> forwardedPairs(final String destination, final String query) {
        final var fragmentStart = destination.indexOf(FRAGMENT_START);
        final var beforeFragment = fragmentStart < 0 ? destination : destination.substring(0, fragmentStart);
        final var queryStart = beforeFragment.indexOf(QUERY_START);
        final var destinationNames = queryStart < 0 ? Set.<String>of() : namesOf(beforeFragment.substring(queryStart + 1));
        return Arrays.stream(query.split(PAIR_SEPARATOR))
                .filter(StringUtils::isNotEmpty)
                .filter(pair -> !QR_MARKER.equals(nameOf(pair)) && !destinationNames.contains(nameOf(pair)));
    }

    private static String valueOf(final String pair) {
        final var nameEnd = pair.indexOf(NAME_END);
        return nameEnd < 0 ? "" : pair.substring(nameEnd + 1);
    }

    private static Stream<String> linksOf(final String value) {
        final var readings = new ArrayList<String>();
        var reading = value;
        for (int round = 0; round <= MAX_DECODE_ROUNDS && !readings.contains(reading); round++) {
            readings.add(reading);
            reading = decodedOnce(reading);
        }
        return readings.stream().map(ForwardedQueries::linkOf).flatMap(Optional::stream);
    }

    private static Optional<String> linkOf(final String value) {
        final var cleaned = IGNORED_IN_LINKS.matcher(value).replaceAll("").strip().replace('\\', '/');
        final var scheme = LINK_SCHEME.matcher(cleaned);
        if (scheme.lookingAt()) {
            return Optional.of(scheme.group(1).toLowerCase(Locale.ROOT) + "://" + StringUtils.stripStart(cleaned.substring(scheme.end()), "/"));
        }
        return cleaned.startsWith("//") ? Optional.of("https://" + StringUtils.stripStart(cleaned, "/")) : Optional.empty();
    }

    private static String decodedOnce(final String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException exception) {
            return value;
        }
    }

    private static String separatorAfter(final String beforeFragment, final int queryStart) {
        if (queryStart < 0) {
            return String.valueOf(QUERY_START);
        }
        return StringUtils.endsWithAny(beforeFragment, String.valueOf(QUERY_START), PAIR_SEPARATOR) ? "" : PAIR_SEPARATOR;
    }

    private static Set<String> namesOf(final String query) {
        return Arrays.stream(query.split(PAIR_SEPARATOR))
                .filter(StringUtils::isNotEmpty)
                .map(ForwardedQueries::nameOf)
                .collect(Collectors.toSet());
    }

    private static String nameOf(final String pair) {
        final var nameEnd = pair.indexOf(NAME_END);
        final var name = nameEnd < 0 ? pair : pair.substring(0, nameEnd);
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException exception) {
            return name;
        }
    }

}
