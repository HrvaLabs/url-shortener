package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.notification.DailySummary;
import cc.hrva.urlshortener.dto.notification.WeeklySummary;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.service.digest.WeeklyDigestText;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMessagesTest {

    private static final String ARROW = Character.toString(0x2192);
    private static final LocalDateTime DEAD_SINCE = LocalDateTime.of(2026, 10, 27, 1, 14);

    private NotificationMessages messages;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("http://localhost:3000/");
        messages = new NotificationMessages(appProperties, Clock.fixed(Instant.parse("2026-10-27T02:30:00Z"), ZoneOffset.UTC),
                new ShortLinks(appProperties));
    }

    @Test
    void dailySummaryListsTheTotalsThenEachLink() {
        final var message = messages.daily(new DailySummary(LocalDate.of(2026, 10, 26), 1241, 1, 7, List.of(
                new DailySummary.SummaryLink("abc", "example.com", 1240, 1, 2, null),
                new DailySummary.SummaryLink("one", "example.org", 1, 0, 1, null),
                new DailySummary.SummaryLink("bots", null, 0, 0, 4, null))));

        assertThat(message).isEqualTo(new NotificationMessage("hrva.cc: clicks on 26 October 2026", List.of(
                "1,241 clicks from 1 visitor, and 7 bot clicks",
                "hrva.cc/abc " + ARROW + " example.com: 1,240 clicks, 1 visitor, 2 bot clicks",
                "hrva.cc/one " + ARROW + " example.org: 1 click, 0 visitors, 1 bot click",
                "hrva.cc/bots " + ARROW + " an unreadable destination: 0 clicks, 0 visitors, 4 bot clicks"),
                "http://localhost:3000/dashboard", 0));
        assertThat(plain(message)).hasSizeLessThanOrEqualTo(NotificationMessages.MOST_CHARACTERS);
    }

    @Test
    void aDailySummaryOfTwentyLongAliasesStaysWithinTheLimit() {
        final var links = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new DailySummary.SummaryLink("a".repeat(120) + index, "example.com", 20 - index, 1, 0, null))
                .toList();

        final var message = messages.daily(new DailySummary(LocalDate.of(2026, 10, 26), 210, 20, 0, links));

        assertThat(plain(message)).hasSizeLessThanOrEqualTo(NotificationMessages.MOST_CHARACTERS).endsWith("http://localhost:3000/dashboard");
    }

    @Test
    void weeklySummaryHasEachSectionThatHasSomething() {
        final var message = messages.weekly(weeklySummary());

        assertThat(message.title()).isEqualTo("hrva.cc: your week, " + WeeklyDigestText.weekLabel(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)));
        assertThat(message.link()).isEqualTo("http://localhost:3000/dashboard");
        assertThat(message.moreCount()).isZero();
        assertThat(message.lines()).isEqualTo(
                List.of(
                        "Clicks: 210 (up 17% on the week before)",
                        "Visitors: 150 (each link's daily visitors, added up)",
                        "Top links:",
                        "1. hrva.cc/abc " + ARROW + " example.com, 90 clicks",
                        "Links that stopped working:",
                        "hrva.cc/dead " + ARROW + " example.net, since 22 October 2026, the page was removed (410)",
                        "Ending soon:",
                        "hrva.cc/soon " + ARROW + " example.org, expires on 30 October 2026",
                        "hrva.cc/limited " + ARROW + " example.org, 1 visit left"));
    }

    @Test
    void weeklySectionsSayHowManyMoreThereAre() {
        final var summary = new WeeklySummary(7L, "ana@example.com", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25),
                new WeeklyDigest.Change(0, 0), 0, List.of(),
                List.of(new DeadLink(3L, 7L, "dead", "example.net", LocalDateTime.of(2026, 10, 22, 1, 0), "410", null)),
                List.of(new WeeklySummary.EndingSoon("soon", "example.org", "EXPIRES", LocalDate.of(2026, 10, 30), null, null)), 4, 1);

        assertThat(messages.weekly(summary).lines()).containsExactly(
                "Links that stopped working:",
                "hrva.cc/dead " + ARROW + " example.net, since 22 October 2026, the page was removed (410)",
                "and 4 more",
                "Ending soon:",
                "hrva.cc/soon " + ARROW + " example.org, expires on 30 October 2026",
                "and 1 more");
        assertThat(messages.weeklySections(summary).deadLinksMore()).isEqualTo("and 4 more");
        assertThat(messages.weeklySections(summary).endingSoonMore()).isEqualTo("and 1 more");
        assertThat(messages.weeklySections(weeklySummary()).deadLinksMore()).isNull();
        assertThat(plain(messages.weekly(summary))).endsWith("and 1 more\nhttp://localhost:3000/dashboard");
    }

    @Test
    void aLongWeeklyMessageNeverEndsOnAHeadingAndCountsOnlyLinks() {
        for (var aliasLength = 1; aliasLength <= 400; aliasLength++) {
            final var alias = "a".repeat(aliasLength);
            final var top = IntStream.rangeClosed(1, 5).mapToObj(index -> new WeeklyDigest.TopLink(alias + "t" + index, "example.com", 10L - index, null)).toList();
            final var dead = IntStream.rangeClosed(1, 20)
                    .mapToObj(index -> new DeadLink(index, 7L, alias + "d" + index, "example.net", LocalDateTime.of(2026, 10, 22, 1, 0), "404", null))
                    .toList();
            final var ending = IntStream.rangeClosed(1, 20)
                    .mapToObj(index -> new WeeklySummary.EndingSoon(alias + "e" + index, "example.org", "EXPIRES", LocalDate.of(2026, 10, 30), null, null))
                    .toList();
            final var summary = new WeeklySummary(7L, "ana@example.com", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25),
                    new WeeklyDigest.Change(50, 40), 30, top, dead, ending, 6, 7);

            final var lines = plain(messages.weekly(summary)).lines().toList();

            final var lastContent = lines.get(lines.size() - 2);
            assertThat(lastContent).as("alias length %d", aliasLength).doesNotEndWith(":");
            final var shownLinks = lines.stream().filter(line -> line.startsWith("hrva.cc/") || line.matches("^\\d+\\. hrva\\.cc/.*")).count()
                    + (lines.contains("and 6 more") ? 6 : 0) + (lines.contains("and 7 more") ? 7 : 0);
            final var hidden = 5 + 20 + 6 + 20 + 7 - shownLinks;
            if (hidden > 0) {
                assertThat(lines.getLast()).as("alias length %d", aliasLength).isEqualTo("and " + hidden + " more: http://localhost:3000/dashboard");
            } else {
                assertThat(lines.getLast()).isEqualTo("http://localhost:3000/dashboard");
            }
        }
    }

    @Test
    void aWeeklySummaryLeavesOutEmptySections() {
        final var summary = new WeeklySummary(7L, "ana@example.com", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25),
                new WeeklyDigest.Change(1, 0), 1, List.of(), List.of(), List.of());

        assertThat(messages.weekly(summary).lines()).containsExactly("Clicks: 1 (none the week before)", "Visitors: 1 (each link's daily visitors, added up)");
    }

    @Test
    void aWeeklySummaryNeverNamesTheEmailAddress() {
        assertThat(plain(messages.weekly(weeklySummary()))).doesNotContain("ana@example.com");
    }

    static WeeklySummary weeklySummary() {
        return new WeeklySummary(7L, "ana@example.com", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), new WeeklyDigest.Change(210, 180), 150,
                List.of(new WeeklyDigest.TopLink("abc", "example.com", 90, null)),
                List.of(new DeadLink(3L, 7L, "dead", "example.net", LocalDateTime.of(2026, 10, 22, 1, 0), "410", null)),
                List.of(new WeeklySummary.EndingSoon("soon", "example.org", "EXPIRES", LocalDate.of(2026, 10, 30), null, null),
                        new WeeklySummary.EndingSoon("limited", "example.org", "VISIT_LIMIT", null, 1L, null)));
    }

    @Test
    void deadLinkLine() {
        final var message = messages.linkDead(List.of(dead("abc", "example.com", "404")));

        assertThat(message).isEqualTo(new NotificationMessage("hrva.cc: 1 link looks broken",
                List.of("hrva.cc/abc " + ARROW + " example.com, since 27 October 2026, the page wasn't found (404)"),
                "http://localhost:3000/dashboard", 0));
    }

    @Test
    void deadLinkLinesShowTheDomain() {
        final var onDomain = new DeadLink(1L, 7L, "menu", "example.org", DEAD_SINCE, "404", "go.example.com");

        assertThat(messages.linkDead(List.of(onDomain, dead("abc", "example.com", "404"))).lines()).containsExactly(
                "go.example.com/menu " + ARROW + " example.org, since 27 October 2026, the page wasn't found (404)",
                "hrva.cc/abc " + ARROW + " example.com, since 27 October 2026, the page wasn't found (404)");
        assertThat(messages.linkBack(List.of(onDomain)).lines())
                .containsExactly("go.example.com/menu " + ARROW + " example.org, works again after looking broken since 27 October 2026");
        assertThat(messages.daily(new DailySummary(LocalDate.of(2026, 10, 26), 3, 2, 0,
                List.of(new DailySummary.SummaryLink("menu", "example.org", 3, 2, 0, "go.example.com")))).lines())
                .contains("go.example.com/menu " + ARROW + " example.org: 3 clicks, 2 visitors, 0 bot clicks");
        final var weekly = new WeeklySummary(7L, "ana@example.com", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25),
                new WeeklyDigest.Change(3, 0), 2, List.of(new WeeklyDigest.TopLink("menu", "example.org", 3, "go.example.com")), List.of(onDomain),
                List.of(new WeeklySummary.EndingSoon("soon", "example.org", "EXPIRES", LocalDate.of(2026, 10, 30), null, "go.example.com")));
        assertThat(messages.weekly(weekly).lines()).contains(
                "1. go.example.com/menu " + ARROW + " example.org, 3 clicks",
                "go.example.com/menu " + ARROW + " example.org, since 27 October 2026, the page wasn't found (404)",
                "go.example.com/soon " + ARROW + " example.org, expires on 30 October 2026");
    }

    @Test
    void theDeadDateIsTheDayInZagreb() {
        final var lateEvening = new DeadLink(1L, 7L, "abc", "example.com", LocalDateTime.of(2026, 10, 26, 23, 30), "410", null);

        assertThat(messages.linkDead(List.of(lateEvening)).lines())
                .containsExactly("hrva.cc/abc " + ARROW + " example.com, since 27 October 2026, the page was removed (410)");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "404|the page wasn't found (404)",
            "410|the page was removed (410)",
            "nxdomain|the website's domain doesn't exist",
            "refused|the website refused the connection",
            "tls|the website's secure connection failed",
            "http-500|the destination stopped working"
    })
    void eachDeadStatusIsWorded(final String code, final String words) {
        assertThat(DestinationStatusWords.of(code)).isEqualTo(words);
        assertThat(messages.linkDead(List.of(dead("abc", "example.com", code))).lines().getFirst()).endsWith(", " + words);
    }

    @Test
    void aLinkWithoutADestinationHostSaysSo() {
        assertThat(messages.linkDead(List.of(dead("abc", null, "404"))).lines().getFirst())
                .startsWith("hrva.cc/abc " + ARROW + " an unreadable destination, since");
    }

    @Test
    void recoveredLinkLine() {
        final var message = messages.linkBack(List.of(new DeadLink(1L, 7L, "abc", "example.com", LocalDateTime.of(2026, 10, 20, 2, 11), null, null)));

        assertThat(message).isEqualTo(new NotificationMessage("hrva.cc: 1 link works again",
                List.of("hrva.cc/abc " + ARROW + " example.com, works again after looking broken since 20 October 2026"),
                "http://localhost:3000/dashboard", 0));
    }

    @Test
    void titlesAreSingularAndPlural() {
        assertThat(messages.linkDead(links(1)).title()).isEqualTo("hrva.cc: 1 link looks broken");
        assertThat(messages.linkDead(links(2)).title()).isEqualTo("hrva.cc: 2 links look broken");
        assertThat(messages.linkBack(links(1)).title()).isEqualTo("hrva.cc: 1 link works again");
        assertThat(messages.linkBack(links(3)).title()).isEqualTo("hrva.cc: 3 links work again");
    }

    @Test
    void theTestMessageNamesTheChannel() {
        final var channel = NotificationChannel.builder().id(4L).name("Team alerts").type(ChannelType.DISCORD).build();

        assertThat(messages.test(channel)).isEqualTo(new NotificationMessage("hrva.cc test: this channel works",
                List.of("This is a test from hrva.cc. Your channel works.", "Channel: Team alerts"),
                "http://localhost:3000/dashboard/settings#notifications", 0));
    }

    @Test
    void renderIsTheTitleTheLinesAndTheLink() {
        assertThat(plain(messages.linkDead(List.of(dead("abc", "example.com", "404"), dead("xyz", "example.org", "tls")))))
                .isEqualTo("""
                        hrva.cc: 2 links look broken
                        hrva.cc/abc %1$s example.com, since 27 October 2026, the page wasn't found (404)
                        hrva.cc/xyz %1$s example.org, since 27 October 2026, the website's secure connection failed
                        http://localhost:3000/dashboard""".formatted(ARROW));
    }

    @Test
    void longListsEndWithMore() {
        final var message = messages.linkDead(links(45));
        final var text = plain(message);

        assertThat(message.lines()).hasSize(20);
        assertThat(message.moreCount()).isEqualTo(25);
        assertThat(text).endsWith("\nand 25 more: http://localhost:3000/dashboard");
        assertThat(text.length()).isLessThanOrEqualTo(NotificationMessages.MOST_CHARACTERS);
        assertThat(text.lines().filter(line -> line.startsWith("hrva.cc/"))).hasSize(20);
    }

    @Test
    void linesThatDoNotFitMoveIntoTheMoreLine() {
        final var longHost = "h".repeat(60) + "." + "i".repeat(60) + "." + "j".repeat(60) + ".example";
        final var many = IntStream.range(0, 20).mapToObj(index -> dead("alias-" + "x".repeat(40) + index, longHost, "404")).toList();
        final var message = messages.linkDead(many);
        final var text = plain(message);

        assertThat(message.lines()).hasSize(20);
        assertThat(text.length()).isLessThanOrEqualTo(1900);
        final var shown = text.lines().filter(line -> line.startsWith("hrva.cc/")).count();
        assertThat(shown).isLessThan(20);
        assertThat(text).endsWith("\nand " + (20 - shown) + " more: http://localhost:3000/dashboard");
    }

    @Test
    void neverCutsASurrogatePair() {
        final var emoji = Character.toString(0x1F600);
        final var message = new NotificationMessage("hrva.cc: " + emoji, IntStream.range(0, 400).mapToObj(index -> emoji.repeat(5)).toList(), "https://x.example", 0);
        final var text = plain(message);

        assertThat(text.length()).isLessThanOrEqualTo(1900);
        assertThat(text.chars().filter(code -> Character.isSurrogate((char) code)).count() % 2).isZero();
        for (int index = 0; index < text.length(); index++) {
            if (Character.isHighSurrogate(text.charAt(index))) {
                assertThat(Character.isLowSurrogate(text.charAt(index + 1))).isTrue();
                index++;
            } else {
                assertThat(Character.isLowSurrogate(text.charAt(index))).isFalse();
            }
        }
        assertThat(text).endsWith(" more: https://x.example");
    }

    private List<DeadLink> links(final int count) {
        return IntStream.range(0, count).mapToObj(index -> dead("link" + index, "example.com", "404")).toList();
    }

    private static DeadLink dead(final String alias, final String host, final String status) {
        return new DeadLink(1L, 7L, alias, host, DEAD_SINCE, status, null);
    }

    private static String plain(final NotificationMessage message) {
        return NotificationMessages.fitted(message, NotificationMessages.Format.PLAIN);
    }

}
