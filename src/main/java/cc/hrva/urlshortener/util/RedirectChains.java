package cc.hrva.urlshortener.util;

import cc.hrva.urlshortener.client.RedirectHop;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public final class RedirectChains {

    private static final String SEPARATOR = "\n";

    private RedirectChains() {}

    public static String join(final List<RedirectHop> hops) {
        return hops.stream().map(RedirectHop::original).collect(Collectors.joining(SEPARATOR));
    }

    public static List<String> split(final String stored) {
        if (StringUtils.isBlank(stored)) {
            return List.of();
        }
        return stored.lines().map(String::strip).filter(StringUtils::isNotEmpty).toList();
    }

}
