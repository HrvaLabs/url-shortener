package cc.hrva.urlshortener.service.alert;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BlocklistSourceFailed;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.BlocklistSourceRecovered;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.LinkBlocked;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.ReviewOpened;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.UserSignedUp;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.VisitorReported;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordAlertFormatterTest {

    private static final AlertedLink ANONYMOUS_LINK = new AlertedLink(42L, "abc12", "example-login.top", null);
    private static final String REVIEW_PAGE = "<https://app.hrva.cc/admin/review>";

    private DiscordAlertFormatter formatter;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setFrontendUrl("https://app.hrva.cc/");
        appProperties.setServerUrl("https://hrva.cc");
        formatter = new DiscordAlertFormatter(appProperties);
    }

    @Test
    void everyMessageDisablesMentionsAndSuppressesEmbeds() {
        final var json = JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(
                formatter.message(List.of(new ReviewOpened(ANONYMOUS_LINK, List.of("login or verification lure"))), 0)));

        assertThat(json.path("allowed_mentions").path("parse").isArray()).isTrue();
        assertThat(json.path("allowed_mentions").path("parse").isEmpty()).isTrue();
        assertThat(json.path("flags").asInt()).isEqualTo(4);
        assertThat(json.has("embeds")).isFalse();
        assertThat(json.path("content").asString()).startsWith("New review: ");
    }

    @Test
    void aNewReviewIsOneLineWithTheShortLinkTheHostTheReasonsAndTheReviewPage() {
        final var message = formatter.message(List.of(new ReviewOpened(ANONYMOUS_LINK,
                List.of("imitates paypal", "domain registered in the last 30 days"))), 0);

        assertThat(message.content()).isEqualTo("New review: hrva.cc/abc12 → example-login.top "
                + "(imitates paypal, domain registered in the last 30 days), anonymous, <https://app.hrva.cc/admin/review?entry=42>");
    }

    @Test
    void onlyTheHostOfTheDestinationIsShown() {
        final var url = Url.builder().id(42L).shortUrl("abc12")
                .longUrl("https://user:secret@Example-Login.top:8443/verify/account?email=alice@example.com#step2")
                .owner(User.builder().id(27L).email("alice@example.com").build())
                .build();

        final var content = formatter.message(List.of(new ReviewOpened(AlertedLink.of(url), List.of("login or verification lure"))), 0).content();

        assertThat(content).contains("hrva.cc/abc12 → example-login.top (login or verification lure), account #27");
        assertThat(content).doesNotContain("verify", "account?", "step2", "secret", "8443", "alice");
    }

    @Test
    void aDestinationThatIsAnIpAddressIsNeverShown() {
        final var ipv4 = Url.builder().id(1L).shortUrl("ip4").longUrl("http://203.0.113.7/login").build();
        final var ipv6 = Url.builder().id(2L).shortUrl("ip6").longUrl("http://[2001:db8::1]:8080/login").build();

        final var content = formatter.message(List.of(
                new ReviewOpened(AlertedLink.of(ipv4), List.of("IP address instead of a domain")),
                new LinkBlocked(AlertedLink.of(ipv6), BlockReason.SAFE_BROWSING, "MALWARE")), 0).content();

        assertThat(content).contains("hrva.cc/ip4 → an IP address", "hrva.cc/ip6 → an IP address");
        assertThat(content).doesNotContain("203.0.113.7", "2001:db8");
    }

    @Test
    void visitorReportDetailsLoseNewlinesCodeAndMaskedLinksAndAreCutToAbout200Characters() {
        final var details = "@everyone see `code` [hrva.cc/admin](https://evil.example/phish)\n# fake heading\n> quote **bold** _it_ ||spoiler|| <@123> "
                + "x".repeat(400);

        final var content = formatter.message(List.of(new VisitorReported(ANONYMOUS_LINK, ReportCategory.PHISHING, details, false)), 0).content();

        assertThat(content).startsWith("Visitor report on an open review: hrva.cc/abc12 → example-login.top (phishing: \"@everyone see code hrva.cc/admin "
                + "<https://evil.example/phish> # fake heading quote \\*\\*bold\\*\\* \\_it\\_ \\|\\|spoiler\\|\\| @123 xxx");
        assertThat(content).doesNotContain("\n", "`", "[", "](", "<@");
        assertThat(content).contains("x…\"), anonymous, <https://app.hrva.cc/admin/review?entry=42>");
        assertThat(content).doesNotContain("x".repeat(200));
    }

    @Test
    void aVisitorReportThatOpensAReviewSaysSo() {
        final var content = formatter.message(List.of(new VisitorReported(
                new AlertedLink(42L, "abc12", "example.top", 27L), ReportCategory.MALWARE, null, true)), 0).content();

        assertThat(content).isEqualTo("New review from a visitor report: hrva.cc/abc12 → example.top (malware), account #27, "
                + "<https://app.hrva.cc/admin/review?entry=42>");
    }

    @Test
    void noEmailAddressOrIpAddressReachesTheMessage() {
        final var content = formatter.message(List.of(
                new VisitorReported(ANONYMOUS_LINK, ReportCategory.OTHER,
                        "mail victim@example.com, sent from 198.51.100.23 and 2001:db8::7, or [::ffff:192.0.2.9].", false),
                new ReviewOpened(ANONYMOUS_LINK, List.of("redirects to 192.0.2.1: IP address instead of a domain")),
                new LinkBlocked(ANONYMOUS_LINK, BlockReason.ADMIN, "reported by owner@example.org from 2001:db8:0:1::5"),
                new UserSignedUp(31L, "google")), 0).content();

        assertThat(content).doesNotContain("@example", "198.51.100.23", "2001:db8", "192.0.2.9", "192.0.2.1");
        assertThat(content).contains("mail (email removed), sent from (IP removed) and (IP removed), or (IP removed).");
        assertThat(content).contains("redirects to (IP removed): IP address instead of a domain");
        assertThat(content).contains("New user: account #31 signed up with Google");
    }

    @Test
    void ordinaryNumbersAndTimesAreNotTakenForIpAddresses() {
        final var content = formatter.message(List.of(new VisitorReported(ANONYMOUS_LINK, ReportCategory.SPAM,
                "costs 9.99 at 12:30:45 on version 1.2.3", false)), 0).content();

        assertThat(content).contains("costs 9.99 at 12:30:45 on version 1.2.3");
    }

    @Test
    void markdownInAnAliasOrHostIsEscaped() {
        final var content = formatter.message(List.of(new ReviewOpened(new AlertedLink(7L, "_my_link", "evil_host.example", null),
                List.of("punycode domain"))), 0).content();

        assertThat(content).startsWith("New review: hrva.cc/\\_my\\_link → evil\\_host.example (punycode domain)");
    }

    @Test
    void eachKindOfAlertHasItsOwnPlainLine() {
        final var content = formatter.message(List.of(
                new LinkBlocked(new AlertedLink(30L, "s30", "login-paypa1.top", null), BlockReason.BLOCKLIST, "HaGeZi TIF medium"),
                new LinkBlocked(new AlertedLink(31L, "s31", "example.org", 7L), BlockReason.SAFE_BROWSING, "SOCIAL_ENGINEERING"),
                new LinkBlocked(new AlertedLink(32L, "s32", "jofogas.cards", null), BlockReason.DOMAIN_RULE, "jofogas.cards"),
                new LinkBlocked(new AlertedLink(33L, "s33", "example.net", null), BlockReason.ADMIN, null),
                new UserSignedUp(31L, "local"),
                new UserSignedUp(32L, "github"),
                new BlocklistSourceFailed("HaGeZi TIF medium", "main: HTTP 503", true),
                new BlocklistSourceFailed("URLhaus", "HTTP 410", false),
                new BlocklistSourceRecovered("HaGeZi TIF medium")), 0).content();

        assertThat(content.lines().toList()).containsExactly(
                "Blocked: hrva.cc/s30 → login-paypa1.top (on HaGeZi TIF medium), anonymous, <https://app.hrva.cc/admin/urls>",
                "Blocked: hrva.cc/s31 → example.org (Safe Browsing: SOCIAL\\_ENGINEERING), account #7, <https://app.hrva.cc/admin/urls>",
                "Blocked: hrva.cc/s32 → jofogas.cards (domain rule jofogas.cards), anonymous, <https://app.hrva.cc/admin/urls>",
                "Blocked: hrva.cc/s33 → example.net (by an admin), anonymous, <https://app.hrva.cc/admin/urls>",
                "New user: account #31 signed up with email, <https://app.hrva.cc/admin>",
                "New user: account #32 signed up with GitHub, <https://app.hrva.cc/admin>",
                "HaGeZi TIF medium failed to load: main: HTTP 503 (a fallback loaded), <https://app.hrva.cc/admin/blocklists>",
                "URLhaus failed to load: HTTP 410, <https://app.hrva.cc/admin/blocklists>",
                "HaGeZi TIF medium loads again");
    }

    @Test
    void moreThanTenAlertsShowTheFirstTenAndCountTheRest() {
        final var alerts = IntStream.range(0, 13)
                .<AdminAlertEvent>mapToObj(index -> new ReviewOpened(new AlertedLink(index, "s" + index, "example.top", null), List.of("high-risk domain ending .top")))
                .toList();

        final var lines = formatter.message(alerts, 0).content().lines().toList();

        assertThat(lines).hasSize(11);
        assertThat(lines.getFirst()).startsWith("New review: hrva.cc/s0 ");
        assertThat(lines.get(9)).startsWith("New review: hrva.cc/s9 ");
        assertThat(lines.getLast()).isEqualTo("…and 3 more, see " + REVIEW_PAGE);
    }

    @Test
    void alertsDroppedFromAFullBufferCountTowardTheRest() {
        final var lines = formatter.message(List.of(new UserSignedUp(31L, "local"), new BlocklistSourceRecovered("URLhaus")), 5)
                .content().lines().toList();

        assertThat(lines).hasSize(3);
        assertThat(lines.getLast()).isEqualTo("…and 5 more, see " + REVIEW_PAGE);
    }

    @Test
    void aBatchOfLongLinesStopsBeforeTwoThousandCharactersAndCountsWhatDidNotFit() {
        final var details = "a_b_".repeat(60);
        final var alerts = IntStream.range(0, 10)
                .<AdminAlertEvent>mapToObj(index -> new VisitorReported(new AlertedLink(index, "s" + index, "example.top", null),
                        ReportCategory.PHISHING, details, false))
                .toList();

        final var content = formatter.message(alerts, 0).content();
        final var lines = content.lines().toList();

        assertThat(content.length()).isLessThanOrEqualTo(2000);
        assertThat(lines.size()).isBetween(2, 10);
        assertThat(lines.getLast()).isEqualTo("…and %d more, see %s".formatted(10 - (lines.size() - 1), REVIEW_PAGE));
    }

    @Test
    void aBatchThatFitsHasNoOverflowLine() {
        final var content = formatter.message(List.of(new BlocklistSourceRecovered("URLhaus")), 0).content();

        assertThat(content).isEqualTo("URLhaus loads again");
    }

}
