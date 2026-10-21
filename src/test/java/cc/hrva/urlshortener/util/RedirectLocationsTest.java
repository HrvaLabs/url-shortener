package cc.hrva.urlshortener.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectLocationsTest {

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", value = {
            "https://evil.example/login page => https://evil.example/login%20page",
            "/\\evil.example/ => //evil.example/",
            "\\\\evil.example\\login => //evil.example/login",
            "\\login => /login",
            "/prijava/čćž?grad=Đakovo => /prijava/%C4%8D%C4%87%C5%BE?grad=%C4%90akovo",
            "/a\"b<c>d{e}f|g^h`i => /a%22b%3Cc%3Ed%7Be%7Df%7Cg%5Eh%60i",
            "/already%20encoded/100% => /already%20encoded/100%25",
            "https://čevapi.example/menu => https://xn--evapi-gya.example/menu",
            "https://user name@čevapi.example:8443/a b#top c => https://user%20name@xn--evapi-gya.example:8443/a%20b#top%20c",
            "http://ｌｏｃａｌｈｏｓｔ/admin => http://localhost/admin",
            "https://[2001:db8::1]:8443/a b => https://[2001:db8::1]:8443/a%20b",
            "http://[invalid => http://[invalid",
            "https://shop.example/offer?id=1 => https://shop.example/offer?id=1"
    })
    void normalisesALocationTheWayABrowserReadsIt(final String location, final String normalised) {
        assertThat(RedirectLocations.normalized(location)).isEqualTo(normalised);
    }

}
