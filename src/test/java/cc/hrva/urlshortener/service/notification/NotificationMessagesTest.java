package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import java.time.Clock;
import java.time.Instant;
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
        messages = new NotificationMessages(appProperties, Clock.fixed(Instant.parse("2026-10-27T02:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void deadLinkLine() {
        final var message = messages.linkDead(List.of(dead("abc", "example.com", "404")));

        assertThat(message).isEqualTo(new NotificationMessage("hrva.cc: 1 link looks broken",
                List.of("hrva.cc/abc " + ARROW + " example.com, since 27 October 2026, the page wasn't found (404)"),
                "http://localhost:3000/dashboard", 0));
    }

    @Test
    void theDeadDateIsTheDayInZagreb() {
        final var lateEvening = new DeadLink(1L, 7L, "abc", "example.com", LocalDateTime.of(2026, 10, 26, 23, 30), "410");

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
        final var message = messages.linkBack(List.of(new DeadLink(1L, 7L, "abc", "example.com", LocalDateTime.of(2026, 10, 20, 2, 11), null)));

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
        assertThat(messages.render(messages.linkDead(List.of(dead("abc", "example.com", "404"), dead("xyz", "example.org", "tls")))))
                .isEqualTo("""
                        hrva.cc: 2 links look broken
                        hrva.cc/abc %1$s example.com, since 27 October 2026, the page wasn't found (404)
                        hrva.cc/xyz %1$s example.org, since 27 October 2026, the website's secure connection failed
                        http://localhost:3000/dashboard""".formatted(ARROW));
    }

    @Test
    void longListsEndWithMore() {
        final var message = messages.linkDead(links(45));
        final var text = messages.render(message);

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
        final var text = messages.render(message);

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
        final var text = messages.render(message);

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
        return new DeadLink(1L, 7L, alias, host, DEAD_SINCE, status);
    }

}
