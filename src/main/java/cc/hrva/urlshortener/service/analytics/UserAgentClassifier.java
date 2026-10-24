package cc.hrva.urlshortener.service.analytics;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

public final class UserAgentClassifier {

    private static final List<String> NAMED_BOTS = List.of(
            "discordbot", "whatsapp", "telegrambot", "slackbot", "slack-imgproxy", "facebookexternalhit", "facebot",
            "twitterbot", "linkedinbot", "skypeuripreview", "googlebot", "bingbot", "applebot", "curl/", "wget/",
            "python-requests", "go-http-client", "okhttp", "java-http-client", "java/", "headlesschrome", "microsoft office/");
    private static final List<String> GENERIC_BOTS = List.of("bot", "crawler", "spider", "preview");
    private static final String CUBOT = "cubot";

    private static final List<Family> BROWSERS = List.of(
            new Family("in-app", "instagram", "fban", "fbav", "fb_iab", "musical_ly", "bytedancewebview", "tiktok"),
            new Family("samsung", "samsungbrowser"),
            new Family("edge", "edg/", "edga/", "edgios/"),
            new Family("opera", "opr/", "opera", "opios"),
            new Family("firefox", "firefox/", "fxios/"),
            new Family("chrome", "crios/", "chrome/", "chromium/"),
            new Family("safari", "safari/"));
    private static final List<Family> SYSTEMS = List.of(
            new Family("ios", "iphone", "ipad", "ipod"),
            new Family("android", "android"),
            new Family("chromeos", "cros "),
            new Family("windows", "windows"),
            new Family("macos", "macintosh", "mac os x"),
            new Family("linux", "linux"));
    private static final List<String> TABLETS = List.of("ipad", "tablet");
    private static final List<String> MOBILES = List.of("mobi", "iphone", "ipod");
    private static final Set<String> DESKTOP_SYSTEMS = Set.of("chromeos", "windows", "macos", "linux");

    private static final String OTHER = "other";
    private static final String UNKNOWN = "unknown";
    private static final UserAgentDetails BOT = new UserAgentDetails(true, UNKNOWN, OTHER, OTHER);

    private UserAgentClassifier() {}

    public static UserAgentDetails classify(final String userAgent) {
        if (StringUtils.isBlank(userAgent)) {
            return BOT;
        }

        final var lowerCase = userAgent.toLowerCase(Locale.ROOT);
        if (isBotLowerCase(lowerCase)) {
            return BOT;
        }

        final var os = firstFamily(lowerCase, SYSTEMS);
        return new UserAgentDetails(false, device(lowerCase, os), firstFamily(lowerCase, BROWSERS), os);
    }

    public static boolean isBot(final String userAgent) {
        return StringUtils.isBlank(userAgent) || isBotLowerCase(userAgent.toLowerCase(Locale.ROOT));
    }

    private static boolean isBotLowerCase(final String lowerCase) {
        return containsAny(lowerCase, NAMED_BOTS) || containsAny(lowerCase.replace(CUBOT, ""), GENERIC_BOTS);
    }

    private static String device(final String lowerCase, final String os) {
        if (containsAny(lowerCase, TABLETS) || lowerCase.contains("android") && !lowerCase.contains("mobile")) {
            return "tablet";
        }
        if (containsAny(lowerCase, MOBILES)) {
            return "mobile";
        }

        return DESKTOP_SYSTEMS.contains(os) ? "desktop" : UNKNOWN;
    }

    private static String firstFamily(final String lowerCase, final List<Family> families) {
        return families.stream()
                .filter(family -> containsAny(lowerCase, family.tokens()))
                .map(Family::name)
                .findFirst()
                .orElse(OTHER);
    }

    private static boolean containsAny(final String lowerCase, final List<String> tokens) {
        return tokens.stream().anyMatch(lowerCase::contains);
    }

    private record Family(String name, List<String> tokens) {

        private Family(final String name, final String... tokens) {
            this(name, List.of(tokens));
        }

    }

}
