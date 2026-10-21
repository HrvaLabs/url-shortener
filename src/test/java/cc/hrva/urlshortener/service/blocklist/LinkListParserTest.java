package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkListParserTest {

    private final LinkListParser linkListParser = new LinkListParser();

    @Test
    void urlhausAndOpenPhishLinesGiveFullKeys() {
        final var parsed = linkListParser.parse(linesOf(
                "################################################################",
                "# abuse.ch URLhaus Database Dump (TXT - online URLs only)      #",
                "################################################################",
                "#",
                "http://113.233.109.131:48875/bin.sh",
                "",
                "https://example.weebly.com/",
                "  HTTPS://Login-Example.COM/Secure/verify.php?id=Ab1#top  "), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder(
                "113.233.109.131:48875/bin.sh", "example.weebly.com/", "login-example.com/Secure/verify.php?id=Ab1");
    }

    @Test
    void junkLinesAndUnparseableLinksAreSkipped() {
        final var parsed = linkListParser.parse(linesOf(
                "not a link",
                "! comment",
                "example.com",
                "ftp://ex.com/file",
                "http://",
                "https://ex.com/a b",
                "http://ex.com/ok"), 100);

        assertThat(parsed.entries()).containsExactly("ex.com/ok");
    }

    @Test
    void spellingsOfOneLinkAreOneEntry() {
        final var parsed = linkListParser.parse(linesOf(
                "http://ex.com/a", "https://ex.com/a", "https://EX.com:443/a", "http://ex.com/a#f",
                "http://two.example/", "http://three.example/", "http://four.example/", "http://five.example/"), 5);

        assertThat(parsed.entries()).containsExactlyInAnyOrder(
                "ex.com/a", "two.example/", "three.example/", "four.example/", "five.example/");
    }

    @Test
    void phishTankCsvGivesOnlyTheUrlColumn() {
        final var parsed = linkListParser.parse(linesOf(
                "phish_id,url,phish_detail_url,submission_time,verified,verification_time,online,target",
                "9001,https://login.example.com/verify,http://www.phishtank.com/phish_detail.php?phish_id=9001,"
                        + "2026-10-21T08:00:00+00:00,yes,2026-10-21T08:10:00+00:00,yes,Other",
                "9002,\"https://ex.com/a,b?x=1\",http://www.phishtank.com/phish_detail.php?phish_id=9002,"
                        + "2026-10-21T08:01:00+00:00,yes,2026-10-21T08:11:00+00:00,yes,\"Bank, Example\""), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder("login.example.com/verify", "ex.com/a,b?x=1");
    }

    @Test
    void htmlEntitiesInCsvLinksAreDecodedOnce() {
        final var parsed = linkListParser.parse(linesOf(
                "phish_id,url,phish_detail_url",
                "9003,https://docs.google.com/presentation/d/e/X/pub?start=false&amp;loop=false&amp;delayms=3000,http://www.phishtank.com/phish_detail.php?phish_id=9003",
                "9004,https://ex.com/q?a=&quot;b&quot;&amp;c=&#39;d&#39;,http://www.phishtank.com/phish_detail.php?phish_id=9004",
                "9005,https://ex.com/twice?a=1&amp;amp;b=2,http://www.phishtank.com/phish_detail.php?phish_id=9005"), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder(
                "docs.google.com/presentation/d/e/X/pub?start=false&loop=false&delayms=3000",
                "ex.com/q?a=\"b\"&c='d'",
                "ex.com/twice?a=1&amp;b=2");
    }

    @Test
    void htmlEscapedAmpersandsInPlainLinksAreDecoded() {
        final var parsed = linkListParser.parse(linesOf("https://plain.example/p?x=1&amp;y=2", "https://plain.example/q?x=1&y=2"), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder("plain.example/p?x=1&y=2", "plain.example/q?x=1&y=2");
    }

    @Test
    void csvFieldsUnescapeQuotesAndShortOrInvalidRowsAreSkipped() {
        final var parsed = linkListParser.parse(linesOf(
                "\"id\",\"URL\"",
                "1,\"https://ex.com/q?a=\"\"b\"\"\"",
                "2",
                "3,not a url",
                "4,",
                "5,http://ok.example/x"), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder("ex.com/q?a=\"b\"", "ok.example/x");
    }

    @Test
    void csvSplitKeepsEmptyFieldsInTheirColumns() {
        assertThat(LinkListParser.csvFields(",a,b")).containsExactly("", "a", "b");
        assertThat(LinkListParser.csvFields("a,,b")).containsExactly("a", "", "b");
        assertThat(LinkListParser.csvFields("\"\",a,b")).containsExactly("", "a", "b");
        assertThat(LinkListParser.csvFields("4,")).containsExactly("4", "");
    }

    @Test
    void phishTankRowWithAnEmptyIdStillGivesTheUrlColumn() {
        final var parsed = linkListParser.parse(linesOf(
                "phish_id,url,phish_detail_url,submission_time,verified,verification_time,online,target",
                ",https://login.example.com/verify,http://www.phishtank.com/phish_detail.php?phish_id=9001,"
                        + "2026-10-21T08:00:00+00:00,yes,2026-10-21T08:10:00+00:00,yes,Other"), 100);

        assertThat(parsed.entries()).containsExactly("login.example.com/verify");
    }

    @Test
    void firstLineDecidesBetweenLinksAndCsv() {
        final var linksWithCommas = linkListParser.parse(linesOf(
                "http://ex.com/a,url,b",
                "id,url",
                "1,http://ex.com/c",
                "http://ex.com/d"), 100);

        assertThat(linksWithCommas.entries()).containsExactlyInAnyOrder("ex.com/a,url,b", "ex.com/d");
    }

    @Test
    void htmlErrorPageIsRejected() {
        final var errorPage = linesOf(
                "<!DOCTYPE html>",
                "<html>",
                "<head><title>502 Bad Gateway</title></head>",
                "<body>",
                "<center><h1>502 Bad Gateway</h1></center>",
                "<a href=\"https://status.example.com/\">status</a>",
                "<hr><center>nginx</center>",
                "</body>",
                "</html>");

        assertThatThrownBy(() -> linkListParser.parse(errorPage, 100))
                .isInstanceOf(BlocklistFormatException.class)
                .hasMessage("No links found. Use a list with one link per line or a CSV with a url column.");
    }

    @Test
    void listWithMoreThanMaxLinksIsRejected() {
        final var sixLinks = linesOf(IntStream.rangeClosed(1, 6)
                .mapToObj(index -> "http://ex.com/" + index)
                .toArray(String[]::new));

        assertThatThrownBy(() -> linkListParser.parse(sixLinks, 5))
                .isInstanceOf(BlocklistFormatException.class);
    }

    @Test
    void csvHeaderWithoutRowsIsRejected() {
        final var headerOnly = linesOf(
                "phish_id,url,phish_detail_url,submission_time,verified,verification_time,online,target");

        assertThatThrownBy(() -> linkListParser.parse(headerOnly, 100))
                .isInstanceOf(BlocklistFormatException.class);
    }

    @Test
    void headerGivesVersionAndDeclaredEntries() {
        final var parsed = linkListParser.parse(linesOf(
                "# Version: 2026.1021",
                "# Number of entries: 2",
                "http://one.example/a",
                "http://two.example/b"), 100);

        assertThat(parsed.version()).isEqualTo("2026.1021");
        assertThat(parsed.declaredEntries()).isEqualTo(2L);
    }

    @Test
    void listWithFarFewerLinksThanDeclaredIsRejected() {
        final var truncated = linesOf(
                "# Number of entries: 100",
                "http://one.example/a",
                "http://two.example/b");

        assertThatThrownBy(() -> linkListParser.parse(truncated, 1000))
                .isInstanceOf(BlocklistFormatException.class);
    }

    private static BufferedReader linesOf(final String... lines) {
        return new BufferedReader(new StringReader(String.join("\n", lines)));
    }

}
