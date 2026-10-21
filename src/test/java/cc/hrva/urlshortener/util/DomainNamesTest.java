package cc.hrva.urlshortener.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @CsvSource({
            "www.njuskalo.hr, njuskalo.hr",
            "shop.example.co.uk, example.co.uk",
            "a.b.example.co.uk, example.co.uk",
            "www.njuskaio.cam, njuskaio.cam",
            "njuskalo.hr, njuskalo.hr"
    })
    void registrableDomainIsTheNameRegisteredUnderThePublicSuffix(final String host, final String registrableDomain) {
        assertThat(DomainNames.registrableDomain(host)).isEqualTo(registrableDomain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x.pages.dev", "user.github.io", "site.netlify.app", "app.vercel.app", "foo.blogspot.com"})
    void registrableDomainKeepsEachSiteOnASharedHostingSuffixApart(final String host) {
        assertThat(DomainNames.registrableDomain(host)).isEqualTo(host);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.2.3.4", "::1", "2001:db8::1", "localhost", "example.unknowntld", "a.b.example.unknowntld", "co.uk",
            "pages.dev", "exa mple.com", ""
    })
    void registrableDomainFallsBackToTheHostItself(final String host) {
        assertThat(DomainNames.registrableDomain(host)).isEqualTo(host);
    }

    @ParameterizedTest
    @CsvSource({
            "x.pages.dev, pages.dev",
            "user.github.io, github.io",
            "bucket.s3.amazonaws.com, amazonaws.com",
            "www.njuskalo.hr, njuskalo.hr",
            "shop.example.co.uk, example.co.uk",
            "pages.dev, pages.dev"
    })
    void registryDomainIsTheNameTheRegistryKnows(final String host, final String registryDomain) {
        assertThat(DomainNames.registryDomain(host)).isEqualTo(registryDomain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3.4", "::1", "localhost", "example.unknowntld", "co.uk", "exa mple.com", ""})
    void registryDomainFallsBackToTheHostItself(final String host) {
        assertThat(DomainNames.registryDomain(host)).isEqualTo(host);
    }

    @Test
    void missingHostHasNoDomain() {
        assertThat(DomainNames.registrableDomain(null)).isNull();
        assertThat(DomainNames.registryDomain(null)).isNull();
    }

}
