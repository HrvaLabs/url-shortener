package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandSitesParserTest {

    private static final String RANKED = "1,google.com\n2,mail.google.com\n3,example.com\n4,amazon.co.uk\n5,paypal.github.io\n6,njuskalo.hr\n";

    private final BrandSitesParser brandSitesParser = new BrandSitesParser(new TopSitesParser());

    @Test
    void keepsOnlyBrandNamedRegistryDomains() {
        final var parsed = brandSitesParser.parse(csvOf(RANKED), 10);

        assertThat(parsed.entries()).containsExactlyInAnyOrder("google.com", "amazon.co.uk", "njuskalo.hr");
    }

    @Test
    void readsOnlyTheTopRows() {
        final var parsed = brandSitesParser.parse(csvOf(RANKED), 2);

        assertThat(parsed.entries()).containsExactly("google.com");
    }

    @Test
    void aListWithoutBrandSitesIsRefused() {
        assertThatThrownBy(() -> brandSitesParser.parse(csvOf("1,example.com\n"), 10))
                .isInstanceOf(BlocklistFormatException.class)
                .hasMessage("No brand sites found in the top sites list.");
    }

    private static BufferedReader csvOf(final String text) {
        return new BufferedReader(new StringReader(text));
    }

}
