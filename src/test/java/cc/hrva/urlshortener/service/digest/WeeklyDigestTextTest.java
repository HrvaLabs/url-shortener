package cc.hrva.urlshortener.service.digest;

import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.WeeklyDigest.Change;
import cc.hrva.urlshortener.dto.WeeklyDigest.TopLink;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyDigestTextTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 19);
    private static final LocalDate TO = LocalDate.of(2026, 10, 25);
    private static final String SHORT_LINK_HOST = "hrva.cc";

    @Test
    void weekLabels() {
        assertThat(WeeklyDigestText.weekLabel(FROM, TO)).isEqualTo("19–25 October 2026");
        assertThat(WeeklyDigestText.weekLabel(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)))
                .isEqualTo("26 October – 1 November 2026");
        assertThat(WeeklyDigestText.weekLabel(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)))
                .isEqualTo("29 December 2025 – 4 January 2026");
    }

    @Test
    void theTitleNamesTheWeek() {
        assertThat(WeeklyDigestText.title(busyWeek())).isEqualTo("hrva.cc weekly summary: 19–25 October 2026");
    }

    @Test
    void changeWording() {
        assertThat(WeeklyDigestText.change(new Change(1204, 1075))).isEqualTo("up 12% on the week before");
        assertThat(WeeklyDigestText.change(new Change(40, 42))).isEqualTo("down 5% on the week before");
        assertThat(WeeklyDigestText.change(new Change(5, 5))).isEqualTo("same as the week before");
        assertThat(WeeklyDigestText.change(new Change(0, 0))).isEqualTo("same as the week before");
        assertThat(WeeklyDigestText.change(new Change(3, 0))).isEqualTo("none the week before");
        assertThat(WeeklyDigestText.change(new Change(1001, 1000))).isEqualTo("up 1% on the week before");
    }

    @Test
    void aChangeIsAtLeastOnePercentAndUsesThousandsSeparators() {
        assertThat(WeeklyDigestText.change(new Change(999, 1000))).isEqualTo("down 1% on the week before");
        assertThat(WeeklyDigestText.change(new Change(0, 7))).isEqualTo("down 100% on the week before");
        assertThat(WeeklyDigestText.change(new Change(2500, 1))).isEqualTo("up 249,900% on the week before");
    }

    @Test
    void linesForABusyWeek() {
        assertThat(WeeklyDigestText.lines(busyWeek(), SHORT_LINK_HOST, UnaryOperator.identity())).containsExactly(
                "Clicks: 1,204 (up 12% on the week before)",
                "Daily unique visitors, added up: 950",
                "Bot share: 20.0%",
                "New links: 40 (down 5% on the week before)",
                "How they were made: 30 website, 6 API key, 4 AI connector",
                "New users: 3 (none the week before)",
                "Active users: 12 signed in during the last 7 days",
                "Reviews: 5 opened, 4 resolved",
                "Automatic blocks: 2 blocklist, 1 Safe Browsing",
                "Top links by clicks:",
                "1. hrva.cc/menu → example.com, 120 clicks");
    }

    @Test
    void linesForAnEmptyWeek() {
        final var empty = new WeeklyDigest(FROM, TO, new Change(0, 0), 0, 0, new Change(0, 0), List.of(), new Change(0, 0), 0, 0, 0,
                List.of(), List.of());

        assertThat(WeeklyDigestText.lines(empty, SHORT_LINK_HOST, UnaryOperator.identity())).containsExactly(
                "Clicks: 0 (same as the week before)",
                "Daily unique visitors, added up: 0",
                "Bot share: no clicks",
                "New links: 0 (same as the week before)",
                "New users: 0 (same as the week before)",
                "Active users: 0 signed in during the last 7 days",
                "Reviews: 0 opened, 0 resolved",
                "Automatic blocks: none",
                "Top links by clicks: none this week");
    }

    @Test
    void oneClickIsSingular() {
        final var digest = withTopLinks(List.of(new TopLink("a", "x.example", 2), new TopLink("b", "y.example", 1)));

        assertThat(WeeklyDigestText.lines(digest, SHORT_LINK_HOST, UnaryOperator.identity())).endsWith(
                "Top links by clicks:",
                "1. hrva.cc/a → x.example, 2 clicks",
                "2. hrva.cc/b → y.example, 1 click");
    }

    @Test
    void botShareHasOneDecimalOfAllClicks() {
        assertThat(WeeklyDigestText.lines(withClicks(2, 1), SHORT_LINK_HOST, UnaryOperator.identity())).contains("Bot share: 33.3%");
        assertThat(WeeklyDigestText.lines(withClicks(0, 5), SHORT_LINK_HOST, UnaryOperator.identity())).contains("Bot share: 100.0%");
        assertThat(WeeklyDigestText.lines(withClicks(7, 0), SHORT_LINK_HOST, UnaryOperator.identity())).contains("Bot share: 0.0%");
    }

    @Test
    void numbersAreEnglishWhateverTheServerLocale() {
        final var serverLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("hr-HR"));

            assertThat(WeeklyDigestText.lines(busyWeek(), SHORT_LINK_HOST, UnaryOperator.identity()))
                    .contains("Clicks: 1,204 (up 12% on the week before)", "Bot share: 20.0%");
            assertThat(WeeklyDigestText.weekLabel(FROM, TO)).isEqualTo("19–25 October 2026");
        } finally {
            Locale.setDefault(serverLocale);
        }
    }

    @Test
    void everyChannelAndBlockReasonHasItsWords() {
        final var digest = new WeeklyDigest(FROM, TO, new Change(10, 10), 5, 0, new Change(1528, 1528),
                List.of(
                        new CountEntry("WEB", 1500),
                        new CountEntry("API_KEY", 12),
                        new CountEntry("BULK", 6),
                        new CountEntry("MCP", 4),
                        new CountEntry("ANONYMOUS_WEB", 3),
                        new CountEntry("ANONYMOUS_MCP", 2),
                        new CountEntry("unknown", 1)),
                new Change(0, 0), 0, 0, 0,
                List.of(
                        new CountEntry("SAFE_BROWSING", 4),
                        new CountEntry("BLOCKLIST", 3),
                        new CountEntry("DOMAIN_RULE", 2),
                        new CountEntry("SCAM_SIGNALS", 1)),
                List.of());

        assertThat(WeeklyDigestText.lines(digest, SHORT_LINK_HOST, UnaryOperator.identity())).contains(
                "How they were made: 1,500 website, 12 API key, 6 bulk, 4 AI connector, 3 website without an account, "
                        + "2 AI connector without an account, 1 unknown",
                "Automatic blocks: 4 Safe Browsing, 3 blocklist, 2 domain rule, 1 scam signals");
    }

    @Test
    void anUnlistedKeyReadsAsLowercaseWordsWithoutUnderscores() {
        final var digest = new WeeklyDigest(FROM, TO, new Change(0, 0), 0, 0, new Change(1, 0), List.of(new CountEntry("NEW_CHANNEL", 1)),
                new Change(0, 0), 0, 0, 0, List.of(new CountEntry("unknown", 2), new CountEntry("MALWARE_FEED", 1)), List.of());

        assertThat(WeeklyDigestText.lines(digest, SHORT_LINK_HOST, UnaryOperator.identity())).contains(
                "How they were made: 1 new channel",
                "Automatic blocks: 2 unknown, 1 malware feed");
    }

    @Test
    void ipAndBlankDestinationsAreNeverShown() {
        final var digest = withTopLinks(List.of(
                new TopLink("ip", "203.0.113.7", 4),
                new TopLink("v6", "2001:db8::1", 3),
                new TopLink("blank", " ", 2),
                new TopLink("none", null, 1)));

        final var lines = WeeklyDigestText.lines(digest, SHORT_LINK_HOST, UnaryOperator.identity());

        assertThat(lines).endsWith(
                "1. hrva.cc/ip → an IP address, 4 clicks",
                "2. hrva.cc/v6 → an IP address, 3 clicks",
                "3. hrva.cc/blank → an unreadable destination, 2 clicks",
                "4. hrva.cc/none → an unreadable destination, 1 click");
        assertThat(String.join("\n", lines)).doesNotContain("203.0.113.7", "2001:db8::1");
    }

    @Test
    void valuesGoThroughTheEscaper() {
        final var digest = withTopLinks(List.of(new TopLink("a_b", "example.com", 3)));

        assertThat(WeeklyDigestText.lines(digest, SHORT_LINK_HOST, value -> "[" + value + "]"))
                .endsWith("1. [hrva.cc/a_b] → [example.com], 3 clicks");
    }

    @Test
    void onlyShortLinksAndDestinationsGoThroughTheEscaperAndNeverARawIp() {
        final var seen = new ArrayList<String>();
        final UnaryOperator<String> value = text -> {
            seen.add(text);
            return "[" + text + "]";
        };
        final var digest = new WeeklyDigest(FROM, TO, new Change(1204, 1075), 950, 301, new Change(40, 42), busyWeek().linksByChannel(),
                new Change(3, 0), 12, 5, 4, busyWeek().automaticBlocks(),
                List.of(new TopLink("a_b", "example.com", 3), new TopLink("ip", "203.0.113.7", 2), new TopLink("blank", "", 1)));

        assertThat(WeeklyDigestText.lines(digest, SHORT_LINK_HOST, value)).containsExactly(
                "Clicks: 1,204 (up 12% on the week before)",
                "Daily unique visitors, added up: 950",
                "Bot share: 20.0%",
                "New links: 40 (down 5% on the week before)",
                "How they were made: 30 website, 6 API key, 4 AI connector",
                "New users: 3 (none the week before)",
                "Active users: 12 signed in during the last 7 days",
                "Reviews: 5 opened, 4 resolved",
                "Automatic blocks: 2 blocklist, 1 Safe Browsing",
                "Top links by clicks:",
                "1. [hrva.cc/a_b] → [example.com], 3 clicks",
                "2. [hrva.cc/ip] → [an IP address], 2 clicks",
                "3. [hrva.cc/blank] → [an unreadable destination], 1 click");
        assertThat(seen).containsExactly(
                "hrva.cc/a_b", "example.com", "hrva.cc/ip", "an IP address", "hrva.cc/blank", "an unreadable destination");
    }

    private static WeeklyDigest busyWeek() {
        return new WeeklyDigest(
                FROM,
                TO,
                new Change(1204, 1075),
                950,
                301,
                new Change(40, 42),
                List.of(new CountEntry("WEB", 30), new CountEntry("API_KEY", 6), new CountEntry("MCP", 4)),
                new Change(3, 0),
                12,
                5,
                4,
                List.of(new CountEntry("BLOCKLIST", 2), new CountEntry("SAFE_BROWSING", 1)),
                List.of(new TopLink("menu", "example.com", 120)));
    }

    private static WeeklyDigest withTopLinks(final List<TopLink> topLinks) {
        final var busy = busyWeek();
        return new WeeklyDigest(busy.from(), busy.to(), busy.clicks(), busy.uniqueVisitors(), busy.botClicks(), busy.newLinks(),
                busy.linksByChannel(), busy.newUsers(), busy.activeUsers(), busy.reviewsOpened(), busy.reviewsResolved(),
                busy.automaticBlocks(), topLinks);
    }

    private static WeeklyDigest withClicks(final long clicks, final long botClicks) {
        final var busy = busyWeek();
        return new WeeklyDigest(busy.from(), busy.to(), new Change(clicks, clicks), clicks, botClicks, busy.newLinks(),
                busy.linksByChannel(), busy.newUsers(), busy.activeUsers(), busy.reviewsOpened(), busy.reviewsResolved(),
                busy.automaticBlocks(), busy.topLinks());
    }

}
