package cc.hrva.urlshortener.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainNamesTest {

    @Test
    void hostIsLowercaseWithoutTrailingDotOrPort() {
        assertThat(DomainNames.hostOf("HTTPS://Example.COM.:443/x")).contains("example.com");
    }

    @Test
    void internationalHostIsConvertedToAscii() {
        assertThat(DomainNames.hostOf("https://bücher.de")).contains("xn--bcher-kva.de");
    }

    @Test
    void ipv6HostLosesItsBrackets() {
        assertThat(DomainNames.hostOf("https://[::1]/")).contains("::1");
    }

    @Test
    void hostIgnoresUserInfo() {
        assertThat(DomainNames.hostOf("https://user@Bücher.de:8080/path")).contains("xn--bcher-kva.de");
    }

    @Test
    void hostIsEmptyForSomethingThatIsNotAUrl() {
        assertThat(DomainNames.hostOf("not a url")).isEmpty();
        assertThat(DomainNames.hostOf(" ")).isEmpty();
        assertThat(DomainNames.hostOf(null)).isEmpty();
    }

    @Test
    void candidatesAreTheHostAndEachParentLongestFirstWithoutTheBareEnding() {
        assertThat(DomainNames.candidates("a.b.example.co.uk"))
                .containsExactly("a.b.example.co.uk", "b.example.co.uk", "example.co.uk", "co.uk");
    }

    @Test
    void candidatesOfARegistrableDomainAreJustThatDomain() {
        assertThat(DomainNames.candidates("example.com")).containsExactly("example.com");
    }

    @Test
    void registrableDomainKeepsCountrySecondLevelLabels() {
        assertThat(DomainNames.registrableDomain("x.example.co.uk")).isEqualTo("example.co.uk");
        assertThat(DomainNames.registrableDomain("www.jofogas.cards")).isEqualTo("jofogas.cards");
    }

}
