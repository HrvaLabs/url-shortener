package cc.hrva.urlshortener.service.inspection;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

final class VirusTotalCandidates {

    private static final String SCHEME_SEPARATOR = "://";

    private VirusTotalCandidates() {
    }

    static List<String> of(final String longUrl, final String finalUrl) {
        final var candidates = new LinkedHashSet<String>();
        addWithItsVariant(candidates, longUrl);
        if (StringUtils.isNotBlank(finalUrl)) {
            addWithItsVariant(candidates, finalUrl);
        }
        return List.copyOf(candidates);
    }

    private static void addWithItsVariant(final LinkedHashSet<String> candidates, final String url) {
        candidates.add(url);
        withSlash(url).ifPresent(candidates::add);
    }

    private static Optional<String> withSlash(final String url) {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (final URISyntaxException exception) {
            return Optional.empty();
        }
        if (uri.isOpaque() || uri.getScheme() == null || uri.getRawAuthority() == null || StringUtils.isNotEmpty(uri.getRawPath())) {
            return Optional.empty();
        }
        final var origin = uri.getScheme() + SCHEME_SEPARATOR + uri.getRawAuthority();
        if (!url.startsWith(origin)) {
            return Optional.empty();
        }
        return Optional.of(origin + "/" + url.substring(origin.length()));
    }

}
