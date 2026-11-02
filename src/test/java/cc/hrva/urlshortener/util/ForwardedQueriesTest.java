package cc.hrva.urlshortener.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ForwardedQueriesTest {

    @ParameterizedTest
    @CsvSource(delimiterString = " | ", value = {
            "https://shop.example/offer | x=1 | https://shop.example/offer?x=1",
            "https://shop.example | x=1 | https://shop.example?x=1",
            "https://shop.example/offer?id=7 | x=1 | https://shop.example/offer?id=7&x=1",
            "https://shop.example/offer? | x=1 | https://shop.example/offer?x=1",
            "https://shop.example/offer?id=7& | x=1 | https://shop.example/offer?id=7&x=1",
            "https://shop.example/offer#reviews | x=1 | https://shop.example/offer?x=1#reviews",
            "https://shop.example/offer?id=7#reviews | x=1 | https://shop.example/offer?id=7&x=1#reviews",
            "https://app.example/#/route?tab=2 | x=1 | https://app.example/?x=1#/route?tab=2",
            "https://shop.example/offer | a=1&a=2 | https://shop.example/offer?a=1&a=2",
            "https://shop.example/offer | flag | https://shop.example/offer?flag",
            "https://shop.example/offer | q=hello%20world&city=%C4%90akovo | https://shop.example/offer?q=hello%20world&city=%C4%90akovo",
            "https://shop.example/offer | q=a+b | https://shop.example/offer?q=a+b"
    })
    void theQueryIsAppendedAfterTheDestinationsOwnAndBeforeItsFragment(final String destination, final String query, final String location) {
        assertThat(ForwardedQueries.appendTo(destination, query)).isEqualTo(location);
    }

    @Test
    void theDestinationsOwnParametersWinOnAClash() {
        assertThat(ForwardedQueries.appendTo("https://shop.example/offer?id=7&utm_source=mail", "id=9&utm_source=x&page=2"))
                .isEqualTo("https://shop.example/offer?id=7&utm_source=mail&page=2");
    }

    @Test
    void aClashIsFoundThroughEncodedNames() {
        assertThat(ForwardedQueries.appendTo("https://shop.example/offer?id=7", "%69d=9&page=2"))
                .isEqualTo("https://shop.example/offer?id=7&page=2");
        assertThat(ForwardedQueries.appendTo("https://shop.example/offer?%69d=7", "id=9"))
                .isEqualTo("https://shop.example/offer?%69d=7");
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", value = {
            "qr => https://shop.example/offer",
            "qr= => https://shop.example/offer",
            "qr=1&x=2 => https://shop.example/offer?x=2",
            "x=2&qr => https://shop.example/offer?x=2",
            "qrcode=1 => https://shop.example/offer?qrcode=1",
            "QR=1 => https://shop.example/offer?QR=1"
    })
    void hrvaCcsOwnQrMarkerIsDropped(final String query, final String location) {
        assertThat(ForwardedQueries.appendTo("https://shop.example/offer", query)).isEqualTo(location);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"&", "&&", "qr"})
    void nothingToForwardLeavesTheDestinationAsItIs(final String query) {
        assertThat(ForwardedQueries.appendTo("https://shop.example/offer?id=7#reviews", query)).isEqualTo("https://shop.example/offer?id=7#reviews");
    }

    @Test
    void aFragmentMarkerInTheQueryCantBecomeAFragment() {
        assertThat(ForwardedQueries.appendTo("https://shop.example/offer#reviews", "x=1#@evil.example"))
                .isEqualTo("https://shop.example/offer?x=1%23@evil.example#reviews");
    }

    @Test
    void aQueryLongerThanTwoKilobytesIsNotForwarded() {
        final var longQuery = "x=" + "a".repeat(2047);

        assertThat(ForwardedQueries.appendTo("https://shop.example/offer", longQuery)).isEqualTo("https://shop.example/offer");
        assertThat(ForwardedQueries.appendTo("https://shop.example/offer", "x=" + "a".repeat(2046))).hasSize(2048 + "https://shop.example/offer?".length());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@evil.example", "x=//evil.example", "\\\\evil.example", "x=1/../../@evil.example", "x=https://evil.example/",
            "%2F%2Fevil.example", "x=1#//evil.example", "x=%0d%0aLocation:%20https://evil.example", "x=\u0000@evil.example"
    })
    void theDestinationHostNeverChanges(final String query) {
        for (final var destination : new String[] {"https://shop.example", "https://shop.example/offer", "https://shop.example/offer?id=7#top",
                "https://user@shop.example:8443"}) {
            final var location = RedirectLocations.normalized(ForwardedQueries.appendTo(destination, query));

            assertThat(RedirectLocations.hostOf(location)).as(destination + " + " + query).isEqualTo("shop.example");
            assertThat(location).as(destination + " + " + query).doesNotContain("\r", "\n");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " | ", value = {
            "url=https://evil.example/login | https://evil.example/login",
            "next=http://evil.example | http://evil.example",
            "redirect=HTTPS://Evil.example/a?b=1 | https://Evil.example/a?b=1",
            "q=https%3A%2F%2Fevil.example%2Flogin | https://evil.example/login",
            "q=https%253A%252F%252Fevil.example | https://evil.example",
            "q=%2F%2Fevil.example | https://evil.example",
            "q=//evil.example | https://evil.example",
            "q=https:%5C%5Cevil.example | https://evil.example",
            "q=https:evil.example | https://evil.example",
            "q=ht%09tps://evil.example | https://evil.example",
            "q=%20https://evil.example | https://evil.example"
    })
    void aForwardedValueThatIsOrDecodesToALinkIsFound(final String query, final String link) {
        assertThat(ForwardedQueries.linksIn("https://shop.example/offer", query)).contains(link);
    }

    @ParameterizedTest
    @ValueSource(strings = {"q=hello", "utm_source=news&page=2", "q=evil.example", "q=ftp://evil.example", "q=javascript:alert(1)", "url", "url=", "q=/home"})
    void aForwardedValueThatIsNoLinkIsNotChecked(final String query) {
        assertThat(ForwardedQueries.linksIn("https://shop.example/offer", query)).isEmpty();
    }

    @Test
    void onlyTheValuesThatWouldBeForwardedAreChecked() {
        assertThat(ForwardedQueries.linksIn("https://shop.example/offer?next=/home", "next=https://evil.example&qr=https://evil.example&u=https://a.example&v=https://a.example"))
                .containsExactly("https://a.example");
        assertThat(ForwardedQueries.linksIn("https://shop.example/offer", null)).isEmpty();
    }

}
