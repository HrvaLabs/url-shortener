package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlocklistParserTest {

    private final BlocklistParser blocklistParser = new BlocklistParser();

    @Test
    void mixedFormatsGiveLowercaseDomainsWithoutCommentsOrJunk() {
        final var domains = blocklistParser.parse(linesOf(
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

        assertThat(domains).containsExactlyInAnyOrder(
                "example.com", "ads.example.net", "x.example.org", "tracker.example.io", "wild.example");
    }

    @Test
    void syntaxAroundTheDomainIsStrippedAndEverythingElseIsSkipped() {
        final var domains = blocklistParser.parse(linesOf(
                "||options.example.com^$third-party",
                "0.0.0.0 0.0.0.0",
                "::1 v6.example.com",
                "0.0.0.0\ttab.example.com",
                "trailing.example.com.",
                "0.0.0.0 commented.example.com # tracker",
                "   padded.example.com   ",
                "192.168.1.1",
                "127.0.0.1 localhost",
                "0.0.0.0 first.example.com second.example.com",
                "@@||allowed.example.com^",
                "example.org##.banner",
                "||path.example.com/ads^",
                "Visit example.net for details"), 100);

        assertThat(domains).containsExactlyInAnyOrder(
                "options.example.com", "v6.example.com", "tab.example.com", "trailing.example.com",
                "commented.example.com", "padded.example.com");
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
                .isInstanceOf(BlocklistFormatException.class);
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
        final var domains = blocklistParser.parse(linesOf(
                "one.example", "two.example", "three.example", "four.example", "five.example", "ONE.example"), 5);

        assertThat(domains).hasSize(5);
    }

    private static BufferedReader linesOf(final String... lines) {
        return new BufferedReader(new StringReader(String.join("\n", lines)));
    }

}
