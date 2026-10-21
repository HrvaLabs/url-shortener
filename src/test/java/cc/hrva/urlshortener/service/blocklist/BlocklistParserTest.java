package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlocklistParserTest {

    private final BlocklistParser blocklistParser = new BlocklistParser();

    @Test
    void mixedFormatsGiveLowercaseDomainsWithoutCommentsOrJunk() {
        final var parsed = blocklistParser.parse(linesOf(
                "# comment",
                "! adblock comment",
                "",
                "example.com",
                "0.0.0.0 ads.example.net",
                "127.0.0.1 x.example.org",
                "||tracker.example.io^",
                "*.wild.example",
                "EXAMPLE.COM",
                "not a domain!",
                "localhost"), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder(
                "example.com", "ads.example.net", "x.example.org", "tracker.example.io", "wild.example");
    }

    @Test
    void syntaxAroundTheDomainIsStrippedAndEverythingElseIsSkipped() {
        final var parsed = blocklistParser.parse(linesOf(
                "||y.com^$important",
                "||doc.example.com^$document,IMPORTANT",
                "||anchored.example.com^|",
                "0.0.0.0 0.0.0.0",
                "::1 v6.example.com",
                "0.0.0.0\ttab.example.com",
                "trailing.example.com.",
                "dots.example.com..",
                "0.0.0.0 commented.example.com # tracker",
                "   padded.example.com   ",
                "192.168.1.1",
                "127.0.0.1 localhost",
                "0.0.0.0 glued.example.com#note",
                "0.0.0.0 first.example.com second.example.com",
                "@@||allowed.example.com^",
                "example.org##.banner",
                "||path.example.com/ads^",
                "||example.com^*/ads/",
                "||example.com^/banner",
                "||x.com^$domain=site.com",
                "||x.com^$badfilter",
                "||options.example.com^$third-party",
                "||*.subdomains.example.com^",
                "Visit example.net for details"), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder(
                "y.com", "doc.example.com", "anchored.example.com", "v6.example.com", "tab.example.com",
                "trailing.example.com", "dots.example.com", "commented.example.com", "padded.example.com");
    }

    @Test
    void adblockExceptionsAndFullUrlsAreDropped() {
        final var parsed = blocklistParser.parse(linesOf(
                "@@||good.example^",
                "https://github.com/user/repo/file.exe",
                "||bad.example^"), 100);

        assertThat(parsed.entries()).containsExactly("bad.example");
    }

    @Test
    void htmlErrorPageIsRejected() {
        final var errorPage = linesOf(
                "<!DOCTYPE html>",
                "<html>",
                "<head><title>502 Bad Gateway</title></head>",
                "<body>",
                "<center><h1>502 Bad Gateway</h1></center>",
                "Visit status.example.com for details",
                "<hr><center>nginx</center>",
                "</body>",
                "</html>");

        assertThatThrownBy(() -> blocklistParser.parse(errorPage, 100))
                .isInstanceOf(BlocklistFormatException.class)
                .hasMessage("No domains found. Use the list's domains, hosts or adblock format.");
    }

    @Test
    void listWithMoreThanMaxDomainsIsRejected() {
        final var sixDomains = linesOf(IntStream.rangeClosed(1, 6)
                .mapToObj(index -> "domain" + index + ".example")
                .toArray(String[]::new));

        assertThatThrownBy(() -> blocklistParser.parse(sixDomains, 5))
                .isInstanceOf(BlocklistFormatException.class);
    }

    @Test
    void listWithExactlyMaxDistinctDomainsIsAccepted() {
        final var parsed = blocklistParser.parse(linesOf(
                "one.example", "two.example", "three.example", "four.example", "five.example", "ONE.example"), 5);

        assertThat(parsed.entries()).containsExactlyInAnyOrder(
                "one.example", "two.example", "three.example", "four.example", "five.example");
    }

    @Test
    void domainListHeaderGivesVersionAndDeclaredEntries() {
        final var parsed = blocklistParser.parse(linesOf(
                "# Title: HaGeZi's Pro DNS Blocklist",
                "# Version: 2026.1020.2231.39",
                "# Number of entries: 3",
                "one.example",
                "two.example",
                "three.example"), 100);

        assertThat(parsed.version()).isEqualTo("2026.1020.2231.39");
        assertThat(parsed.declaredEntries()).isEqualTo(3L);
    }

    @Test
    void adblockHeaderGivesVersion() {
        final var parsed = blocklistParser.parse(linesOf(
                "[Adblock Plus]",
                "! Version: 1.2",
                "||one.example^"), 100);

        assertThat(parsed.version()).isEqualTo("1.2");
    }

    @Test
    void entriesHeaderIsReadAsDeclaredEntries() {
        final var parsed = blocklistParser.parse(linesOf(
                "# entries: 3",
                "one.example",
                "two.example",
                "three.example"), 100);

        assertThat(parsed.declaredEntries()).isEqualTo(3L);
    }

    @Test
    void firstHeaderValueWins() {
        final var parsed = blocklistParser.parse(linesOf(
                "# Version: 1",
                "# Number of entries: 1",
                "one.example",
                "# Version: 2",
                "# Number of entries: 50"), 100);

        assertThat(parsed.version()).isEqualTo("1");
        assertThat(parsed.declaredEntries()).isEqualTo(1L);
    }

    @Test
    void listWithFarFewerDomainsThanDeclaredIsRejected() {
        final var truncated = linesOf(
                "# Number of entries: 100",
                "one.example",
                "two.example",
                "three.example");

        assertThatThrownBy(() -> blocklistParser.parse(truncated, 1000))
                .isInstanceOf(BlocklistFormatException.class);
    }

    @Test
    void listWithoutHeaderHasNoVersionOrDeclaredEntries() {
        final var parsed = blocklistParser.parse(linesOf("one.example", "two.example"), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder("one.example", "two.example");
        assertThat(parsed.version()).isNull();
        assertThat(parsed.declaredEntries()).isNull();
    }

    @Test
    void hostsHeaderGivesUniqueDomainCount() {
        final var parsed = blocklistParser.parse(linesOf(
                "# Title: StevenBlack/hosts",
                "# Number of unique domains: 3",
                "0.0.0.0 one.example",
                "0.0.0.0 two.example",
                "0.0.0.0 three.example"), 100);

        assertThat(parsed.declaredEntries()).isEqualTo(3L);
    }

    @Test
    void declaredCountWithThousandsSeparatorIsParsed() {
        final var lines = Stream.concat(
                        Stream.of("# Number of unique domains: 1,000"),
                        IntStream.rangeClosed(1, 1000).mapToObj(index -> "0.0.0.0 domain" + index + ".example"))
                .toArray(String[]::new);

        final var parsed = blocklistParser.parse(linesOf(lines), 5000);

        assertThat(parsed.declaredEntries()).isEqualTo(1000L);
    }

    @Test
    void ruleCountIsNotTakenForADomainCount() {
        final var parsed = blocklistParser.parse(linesOf(
                "! Total number of network filters: 100",
                "||one.example^",
                "||two.example^",
                "||three.example^"), 100);

        assertThat(parsed.declaredEntries()).isNull();
        assertThat(parsed.entries()).hasSize(3);
    }

    private static BufferedReader linesOf(final String... lines) {
        return new BufferedReader(new StringReader(String.join("\n", lines)));
    }

}
