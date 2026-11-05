package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopSitesParserTest {

    private final TopSitesParser topSitesParser = new TopSitesParser();

    @Test
    void readsRankAndDomainLinesWithCrlf() {
        final var parsed = topSitesParser.parse(csvOf("1,google.com\r\n2,microsoft.com\r\n3,mail.ru\r\n"), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder("google.com", "microsoft.com", "mail.ru");
        assertThat(parsed.version()).isNull();
        assertThat(parsed.declaredEntries()).isNull();
    }

    @Test
    void stopsAtTheLimit() {
        final var parsed = topSitesParser.parse(csvOf("1,google.com\r\n2,microsoft.com\r\n3,mail.ru\r\n4,facebook.com\r\n"), 2);

        assertThat(parsed.entries()).containsExactlyInAnyOrder("google.com", "microsoft.com");
    }

    @Test
    void skipsBlankAndMalformedLines() {
        final var parsed = topSitesParser.parse(csvOf(String.join("\r\n",
                "rank,domain",
                "",
                "1,google.com",
                "google.com",
                "x,facebook.com",
                "2,",
                "3,exa mple.com",
                "4,1.2.3.4",
                "5,localhost",
                "6,a..b.com",
                "7,linkedin.com")), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder("google.com", "linkedin.com");
    }

    @Test
    void lowerCasesAndKeepsAscii() {
        final var parsed = topSitesParser.parse(csvOf("1,Google.COM\r\n2,xn--bcher-kva.de\r\n3,bücher.at\r\n4,njuskalo.hr.\r\n"), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder("google.com", "xn--bcher-kva.de", "xn--bcher-kva.at", "njuskalo.hr");
    }

    @Test
    void aDownloadWithoutRankedDomainsIsRefused() {
        assertThatThrownBy(() -> topSitesParser.parse(csvOf("<html>\r\n<body>502 Bad Gateway</body>\r\n</html>\r\n"), 100))
                .isInstanceOf(BlocklistFormatException.class)
                .hasMessage("No domains found. Use a top sites list with rank,domain lines.");
    }

    private static BufferedReader csvOf(final String csv) {
        return new BufferedReader(new StringReader(csv));
    }

}
