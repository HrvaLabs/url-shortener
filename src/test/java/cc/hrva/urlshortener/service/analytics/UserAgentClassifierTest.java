package cc.hrva.urlshortener.service.analytics;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentClassifierTest {

    private static final String WINDOWS_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
    private static final String IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1";
    private static final String IPHONE_APP = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148";

    @ParameterizedTest
    @MethodSource("userAgents")
    void userAgentIsClassifiedAsBotOrIntoDeviceBrowserAndOsFamilies(final String userAgent, final boolean bot,
            final String device, final String browser, final String os) {
        assertThat(UserAgentClassifier.classify(userAgent)).isEqualTo(new UserAgentDetails(bot, device, browser, os));
        assertThat(UserAgentClassifier.isBot(userAgent)).isEqualTo(UserAgentClassifier.classify(userAgent).bot());
    }

    private static Stream<Arguments> userAgents() {
        return Stream.of(
                human(WINDOWS_CHROME, "desktop", "chrome", "windows"),
                human(IPHONE_SAFARI, "mobile", "safari", "ios"),
                human(IPHONE_SAFARI.replace("iPhone; CPU iPhone OS 18_6", "iPad; CPU OS 18_6"), "tablet", "safari", "ios"),
                human("Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "SamsungBrowser/28.0 Chrome/130.0.0.0 Mobile Safari/537.36", "mobile", "samsung", "android"),
                human("Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/141.0.0.0 Safari/537.36", "tablet", "chrome", "android"),
                human("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0", "desktop", "edge", "macos"),
                human(WINDOWS_CHROME + " OPR/125.0.0.0", "desktop", "opera", "windows"),
                human("Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0", "desktop", "firefox",
                        "linux"),
                human("Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/141.0.0.0 Safari/537.36", "desktop", "chrome", "chromeos"),
                human("Microsoft Office/16.0 (Windows NT 10.0; Microsoft Outlook 16.0.17928; Pro)", "desktop", "other",
                        "windows"),
                human(IPHONE_APP + " Instagram 400.0.0.0.0", "mobile", "in-app", "ios"),
                human(IPHONE_APP + " [FBAN/FBIOS;FBAV/500.0.0.0]", "mobile", "in-app", "ios"),
                human("Mozilla/5.0 (Linux; Android 10; CUBOT P40) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/141.0.0.0 Mobile Safari/537.36", "mobile", "chrome", "android"),
                human("SomethingWeird/1.0", "unknown", "other", "other"),
                bot(null),
                bot(""),
                bot("   "),
                bot("Discordbot/2.0"),
                bot("WhatsApp/2.23.20.0 A"),
                bot("TelegramBot (like TwitterBot)"),
                bot("Slackbot-LinkExpanding 1.0"),
                bot("Slack-ImgProxy 0.19"),
                bot("facebookexternalhit/1.1"),
                bot("facebot/1.0"),
                bot("Twitterbot/1.0"),
                bot("LinkedInBot/1.0"),
                bot("SkypeUriPreview Preview/0.5"),
                bot("Mozilla/5.0 (compatible; GoogleBot/2.1; +http://www.google.com/bot.html)"),
                bot("Mozilla/5.0 (compatible; bingbot/2.0)"),
                bot("Applebot/0.1"),
                bot("curl/8.7.1"),
                bot("Wget/1.21.4"),
                bot("python-requests/2.32.3"),
                bot("Go-http-client/2.0"),
                bot("okhttp/4.12.0"),
                bot("Java-http-client/25"),
                bot("Java/1.8.0_412"),
                bot(WINDOWS_CHROME.replace("Chrome/141.0.0.0", "HeadlessChrome/141.0.0.0")),
                bot("AhrefsBot/7.0"),
                bot("Some Crawler 1.0"),
                bot("MySpider"),
                bot("LinkPreview/1.0"));
    }

    private static Arguments human(final String userAgent, final String device, final String browser, final String os) {
        return Arguments.of(userAgent, false, device, browser, os);
    }

    private static Arguments bot(final String userAgent) {
        return Arguments.of(userAgent, true, "unknown", "other", "other");
    }

}
