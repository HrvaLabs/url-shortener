package cc.hrva.urlshortener.util;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class BlockDetails {

    private static final String SEPARATOR = ", ";

    private BlockDetails() {
    }

    public static String flaggedUrls(final String detail, final int maxLength) {
        if (detail == null || detail.length() <= maxLength) {
            return detail;
        }
        return flaggedUrls(Arrays.asList(detail.split(SEPARATOR)), maxLength);
    }

    public static String flaggedUrls(final List<String> urls, final int maxLength) {
        final var distinct = urls.stream().distinct().toList();
        return listed(distinct, maxLength)
                .or(() -> listed(distinct.stream().map(BlockDetails::hostOf).distinct().toList(), maxLength))
                .orElseGet(() -> distinct.size() + (distinct.size() == 1 ? " flagged address" : " flagged addresses"));
    }

    private static Optional<String> listed(final List<String> items, final int maxLength) {
        for (var shown = items.size(); shown > 0; shown--) {
            final var hidden = items.size() - shown;
            final var text = String.join(SEPARATOR, items.subList(0, shown)) + (hidden > 0 ? SEPARATOR + "and " + hidden + " more" : "");
            if (text.length() <= maxLength) {
                return Optional.of(text);
            }
        }
        return items.isEmpty() ? Optional.of("") : Optional.empty();
    }

    private static String hostOf(final String url) {
        return DomainNames.hostOf(url).orElse(url);
    }

}
