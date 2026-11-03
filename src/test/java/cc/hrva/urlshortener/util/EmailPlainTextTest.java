package cc.hrva.urlshortener.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailPlainTextTest {

    @Test
    void headingsAndParagraphsBecomeBlocksSeparatedByABlankLine() {
        final var html = "<h1>Reset your password</h1><p>Hi Ana,</p><p>Use the button   below\n to choose a new one.</p>";

        assertThat(EmailPlainText.of(html)).isEqualTo("Reset your password\n\nHi Ana,\n\nUse the button below to choose a new one.");
    }

    @Test
    void aLinkWhoseTextDiffersFromItsAddressShowsBoth() {
        final var html = "<p><a href=\"https://app.hrva.cc/dashboard\">Sign in</a></p>";

        assertThat(EmailPlainText.of(html)).isEqualTo("Sign in: https://app.hrva.cc/dashboard");
    }

    @Test
    void aLinkInsideASentenceShowsItsAddressInBrackets() {
        final var html = "<p>Go to <a href=\"https://app.hrva.cc/dashboard\">your dashboard</a> to check.</p>";

        assertThat(EmailPlainText.of(html)).isEqualTo("Go to your dashboard (https://app.hrva.cc/dashboard) to check.");
    }

    @Test
    void aLinkWhoseTextIsItsAddressShowsItOnce() {
        final var html = "<p><a href=\"https://hrva.cc/help\">https://hrva.cc/help</a></p>"
                + "<p>Write to <a href=\"mailto:support@hrva.cc\">support@hrva.cc</a>.</p>";

        assertThat(EmailPlainText.of(html)).isEqualTo("https://hrva.cc/help\n\nWrite to support@hrva.cc.");
    }

    @Test
    void aLinkWithoutAUsableAddressKeepsOnlyItsText() {
        final var html = "<p><a href=\"#top\">Top</a> and <a>Plain</a></p>";

        assertThat(EmailPlainText.of(html)).isEqualTo("Top and Plain");
    }

    @Test
    void listItemsStartWithADash() {
        final var html = "<p>You can:</p><ul><li>Sign in</li><li>Reset <b>your</b> password</li></ul><p>Done.</p>";

        assertThat(EmailPlainText.of(html)).isEqualTo("You can:\n\n- Sign in\n- Reset your password\n\nDone.");
    }

    @Test
    void tableRowsJoinCellsWithATab() {
        final var html = "<table><tr><td>Links</td><td>12</td></tr><tr><td>Clicks</td><td>340</td></tr></table>";

        assertThat(EmailPlainText.of(html)).isEqualTo("Links\t12\nClicks\t340");
    }

    @Test
    void layoutTablesAreWalkedAndALabelAndItsDetailStayOnTheirOwnLines() {
        final var html = "<table role=\"presentation\"><tr><td><table><tr><td><h1>Domain removed</h1>"
                + "<p>We removed go.example.com.</p>"
                + "<table><tr><td><div>Link</div><div>hrva.cc/menu</div></td></tr><tr><td>hrva.cc/promo</td></tr></table>"
                + "<p>Contact us.</p></td></tr></table></td></tr></table>";

        assertThat(EmailPlainText.of(html)).isEqualTo("Domain removed\n\nWe removed go.example.com.\n\nLink\nhrva.cc/menu\nhrva.cc/promo\n\nContact us.");
    }

    @Test
    void hiddenPreheaderAndStyleAndScriptAreDropped() {
        final var html = "<html><head><title>Subject</title><style>p { color: red; }</style></head><body>"
                + "<div style=\"display: none; max-height: 0\">Preview text&nbsp;&zwnj;&nbsp;</div>"
                + "<style>h1 { margin: 0; }</style><script>alert(1)</script><!-- a comment -->"
                + "<p>Body</p><span style=\"DISPLAY:NONE\">Secret</span></body></html>";

        assertThat(EmailPlainText.of(html)).isEqualTo("Body");
    }

    @Test
    void noMoreThanOneBlankLineInARow() {
        final var html = "<p>One</p><br><br><br><p></p><p>&nbsp;</p><div> </div><p>Two</p><br><br>";

        assertThat(EmailPlainText.of(html)).isEqualTo("One\n\nTwo");
    }

    @Test
    void aLineBreakStartsANewLine() {
        assertThat(EmailPlainText.of("<p>Line one<br>Line two</p>")).isEqualTo("Line one\nLine two");
    }

    @Test
    void entitiesAreDecoded() {
        assertThat(EmailPlainText.of("<p>hrva.cc &rarr; example.com &middot; 5&nbsp;links</p>")).isEqualTo("hrva.cc → example.com · 5 links");
    }

    @Test
    void nothingGivesAnEmptyText() {
        assertThat(EmailPlainText.of(null)).isEmpty();
        assertThat(EmailPlainText.of("")).isEmpty();
        assertThat(EmailPlainText.of("<div style=\"display:none\">x</div>")).isEmpty();
    }

}
