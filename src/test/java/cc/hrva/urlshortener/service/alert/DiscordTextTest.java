package cc.hrva.urlshortener.service.alert;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordTextTest {

    @Test
    void blankIsEmpty() {
        assertThat(DiscordText.plain(null)).isEmpty();
        assertThat(DiscordText.plain("   ")).isEmpty();
    }

    @Test
    void newlinesCodeAndMaskedLinksAreDefused() {
        final var details = "@everyone see `code` [hrva.cc/admin](https://evil.example/phish)\n# fake heading\n> quote **bold** _it_ ||spoiler|| <@123> "
                + "x".repeat(400);

        assertThat(DiscordText.plain(details)).isEqualTo("@everyone see code hrva.cc/admin <https://evil.example/phish> # fake heading quote "
                + "\\*\\*bold\\*\\* \\_it\\_ \\|\\|spoiler\\|\\| @123 " + "x".repeat(400));
    }

    @Test
    void noEmailAddressOrIpAddressSurvives() {
        assertThat(DiscordText.plain("mail victim@example.com, sent from 198.51.100.23 and 2001:db8::7, or [::ffff:192.0.2.9]."))
                .isEqualTo("mail (email removed), sent from (IP removed) and (IP removed), or (IP removed).");
    }

    @Test
    void ordinaryNumbersAndTimesAreNotTakenForIpAddresses() {
        assertThat(DiscordText.plain("costs 9.99 at 12:30:45 on version 1.2.3")).isEqualTo("costs 9.99 at 12:30:45 on version 1.2.3");
    }

    @Test
    void markdownInAnAliasOrHostIsEscaped() {
        assertThat(DiscordText.plain("hrva.cc/_my_link")).isEqualTo("hrva.cc/\\_my\\_link");
        assertThat(DiscordText.plain("a_b*c.example ~x~ |y| \\z")).isEqualTo("a\\_b\\*c.example \\~x\\~ \\|y\\| \\\\z");
    }

    @Test
    void invisibleCharactersBecomeSpaces() {
        final var hidden = "a" + Character.toString(0x200B) + "b" + Character.toString(0x202E) + "c" + Character.toString(0x2028) + "d";

        assertThat(DiscordText.plain(hidden)).isEqualTo("a b c d");
    }

    @Test
    void webAddressesAreWrappedSoTheyAreNotEscaped() {
        assertThat(DiscordText.plain("see https://example.com/a_b*c now")).isEqualTo("see <https://example.com/a_b*c> now");
    }

}
