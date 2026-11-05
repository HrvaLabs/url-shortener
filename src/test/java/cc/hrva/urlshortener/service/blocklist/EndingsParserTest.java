package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndingsParserTest {

    private final EndingsParser endingsParser = new EndingsParser();

    @Test
    void readsOneEndingPerLine() {
        final var parsed = endingsParser.parse(textOf("# Title: x\ntop\nBET\n.cam\n*.icu\nxn--6frz82g\n"), 100);

        assertThat(parsed.entries()).containsExactlyInAnyOrder("top", "bet", "cam", "icu", "xn--6frz82g");
    }

    @Test
    void skipsLinesThatAreNotASingleLabel() {
        final var parsed = endingsParser.parse(textOf("example.com\n-bad\nok\n"), 100);

        assertThat(parsed.entries()).containsExactly("ok");
    }

    @Test
    void anEmptyListIsRefused() {
        assertThatThrownBy(() -> endingsParser.parse(textOf("# only a comment\n! another\n"), 100))
                .isInstanceOf(BlocklistFormatException.class)
                .hasMessage("No endings found. Use a list with one top-level domain per line.");
    }

    @Test
    void stopsAtTheLimit() {
        assertThatThrownBy(() -> endingsParser.parse(textOf("top\nbet\ncam\n"), 2))
                .isInstanceOf(BlocklistFormatException.class)
                .hasMessage("The list has more than 2 endings");
    }

    private static BufferedReader textOf(final String text) {
        return new BufferedReader(new StringReader(text));
    }

}
