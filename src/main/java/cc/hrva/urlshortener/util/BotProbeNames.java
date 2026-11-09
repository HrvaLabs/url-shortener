package cc.hrva.urlshortener.util;

import java.util.Locale;
import java.util.Set;

public final class BotProbeNames {

    private static final Set<String> NAMES = Set.of(
            "favicon.ico", "favicon.png", "robots.txt", "sitemap.xml",
            ".env", ".git", ".ds_store", "wp-login.php", "wp-admin",
            "xmlrpc.php", "phpinfo.php", "config.json", ".well-known");

    private BotProbeNames() {
    }

    public static boolean contains(final String name) {
        return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT));
    }
}
