package cc.hrva.urlshortener.service.campaign;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

public final class UrlShapes {

    public static final List<String> GENERIC_PATHS = List.of("/index.php", "/index.html", "/index.htm", "/search", "/watch", "/article", "/post",
            "/p", "/s", "/share", "/view", "/item", "/product");

    private static final String ROOT = "/";
    private static final String UTM_PREFIX = "utm_";
    private static final int MIN_ROOT_NAMES = 2;

    private UrlShapes() {}

    public static Optional<UrlShape> of(final String longUrl) {
        return parsed(longUrl).filter(shape -> !isGeneric(shape));
    }

    private static Optional<UrlShape> parsed(final String longUrl) {
        if (StringUtils.isBlank(longUrl)) {
            return Optional.empty();
        }
        try {
            final var uri = URI.create(longUrl);
            if (uri.isOpaque() || uri.getRawPath() == null) {
                return Optional.empty();
            }
            return Optional.of(new UrlShape(pathOf(uri.getRawPath()), queryNamesOf(uri.getRawQuery())));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String pathOf(final String rawPath) {
        final var path = StringUtils.stripEnd(rawPath.toLowerCase(Locale.ROOT), ROOT);
        return path.isEmpty() ? ROOT : path;
    }

    private static List<String> queryNamesOf(final String rawQuery) {
        if (StringUtils.isEmpty(rawQuery)) {
            return List.of();
        }
        return Arrays.stream(rawQuery.split("&"))
                .map(parameter -> StringUtils.substringBefore(parameter, "=").toLowerCase(Locale.ROOT))
                .filter(name -> !name.isBlank() && !name.startsWith(UTM_PREFIX))
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean isGeneric(final UrlShape shape) {
        return GENERIC_PATHS.contains(shape.path()) || ROOT.equals(shape.path()) && shape.queryNames().size() < MIN_ROOT_NAMES;
    }

}
