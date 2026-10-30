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
            "https://\u0430\u0440\u0440\u04cf\u0435.com/id => https://xn--80ak6aa92e.com/id",
            "https://\u0410\u0420\u0420\u04c0\u0415.com/id => https://xn--80ak6aa92e.com/id",
            "http://0x7f.0.0.1/admin => http://127.0.0.1/admin",
            "http://2130706433:8080/x => http://127.0.0.1:8080/x",
            "https://[2001:db8::1]:8443/a b => https://[2001:db8::1]:8443/a%20b",
            "http://[invalid => http://[invalid",
            "https://shop.example/offer?id=1 => https://shop.example/offer?id=1"
    })
    void normalisesALocationTheWayABrowserReadsIt(final String location, final String normalised) {
        assertThat(RedirectLocations.normalized(location)).isEqualTo(normalised);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", value = {
            "https://xn--evapi-gya.example/menu => xn--evapi-gya.example",
            "https://user%20name@xn--evapi-gya.example:8443/a%20b#top => xn--evapi-gya.example",
            "https://shop.example?id=1 => shop.example",
            "https://shop.example#top => shop.example",
            "https://shop.example => shop.example",
            "https://[2001:db8::1]:8443/a => [2001:db8::1]",
            "https://čokolada.hr/torta => čokolada.hr"
    })
    void readsTheHostOfALocationAsWritten(final String location, final String host) {
        assertThat(RedirectLocations.hostOf(location)).isEqualTo(host);
    }

}
