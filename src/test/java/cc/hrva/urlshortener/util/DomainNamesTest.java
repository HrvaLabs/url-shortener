package cc.hrva.urlshortener.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
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

    @ParameterizedTest
    @CsvSource({
            "https://p\u0430ypal.com/login, xn--pypal-4ve.com",
            "https://xn--pypal-4ve.com/login, xn--pypal-4ve.com",
            "https://\u0430\u0440\u0440\u04cf\u0435.com/, xn--80ak6aa92e.com",
            "https://\u0410\u0420\u0420\u04c0\u0415.com/, xn--80ak6aa92e.com",
            "https://\uff42\uff49\uff54\uff0e\uff4c\uff59/x, bit.ly",
            "https://my_shop.duckdns.org/, my_shop.duckdns.org"
    })
    void internationalHostIsConvertedToTheAsciiFormABrowserVisits(final String url, final String host) {
        assertThat(DomainNames.hostOf(url)).contains(host);
    }

    @ParameterizedTest
    @CsvSource({
            "https://p\u0430ypal.com/login, xn--pypal-4ve.com, p\u0430ypal.com",
            "https://XN--PYPAL-4VE.COM./login, xn--pypal-4ve.com, p\u0430ypal.com",
            "https://\u0410\u0420\u0420\u04c0\u0415.com/, xn--80ak6aa92e.com, \u0430\u0440\u0440\u04cf\u0435.com",
            "https://Čokolada.HR\uff0e/, xn--okolada-i6a.hr, čokolada.hr",
            "https://xn--zz.com/, xn--zz.com, xn--zz.com",
            "https://[2001:DB8::1]:8443/, 2001:db8::1, 2001:db8::1"
    })
    void hostNameHasTheAsciiFormAndTheLowercaseUnicodeForm(final String url, final String ascii, final String unicode) {
        assertThat(DomainNames.hostNameOf(url)).contains(new HostName(ascii, unicode));
    }

    @ParameterizedTest
    @CsvSource({
            "http://0x7f.0.0.1/, 127.0.0.1",
            "http://0X7F000001/login, 127.0.0.1",
            "http://0177.0.0.1/, 127.0.0.1",
            "http://2130706433/, 127.0.0.1",
            "http://127.1/, 127.0.0.1",
            "http://01.2.3.4/, 1.2.3.4",
            "http://1.2.3.4./, 1.2.3.4",
            "http://0x.0.0.0x10/, 0.0.0.16"
    })
    void hostThatABrowserReadsAsAnIpv4AddressIsThatAddress(final String url, final String address) {
        assertThat(DomainNames.hostOf(url)).contains(address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3.256", "999.1.1.1", "1.2.3.4.5", "0x7g.0.0.1", "example.123", "4.example"})
    void hostThatIsNoIpv4AddressStaysAsWritten(final String host) {
        assertThat(DomainNames.hostOf("http://" + host + "/")).contains(host);
    }

    @Test
    void hostNameAllowsLabelsOfUpTo63Characters() {
        assertThat(DomainNames.HOST_NAME.matcher("a".repeat(63) + ".bit.ly").matches()).isTrue();
        assertThat(DomainNames.HOST_NAME.matcher("a".repeat(64) + ".bit.ly").matches()).isFalse();
        assertThat(DomainNames.HOST_NAME.matcher("www." + "b".repeat(64) + ".com").matches()).isFalse();
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

    @ParameterizedTest
    @ValueSource(strings = {"amazon.co.uk", "login.amazon.de", "x.pages.dev", "pages.dev", "njuskalo.hr"})
    void nameUnderAKnownRegistrySuffixIsUnderARegistrySuffix(final String host) {
        assertThat(DomainNames.isUnderRegistrySuffix(host)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"amazon.unknowntld", "amazon.evil.unknowntld", "localhost", "co.uk", "1.2.3.4", "::1", "exa mple.com"})
    void nameUnderAnUnknownEndingOrNotADomainIsNotUnderARegistrySuffix(final String host) {
        assertThat(DomainNames.isUnderRegistrySuffix(host)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ddns.net", "pages.dev", "github.io", "duckdns.org", "co.uk", "com"})
    void sharedHostingDomainOrTopLevelEndingIsAPublicSuffix(final String host) {
        assertThat(DomainNames.isPublicSuffix(host)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"x.pages.dev", "scam.ddns.net", "jofogas.cards", "amazon.co.uk", "localhost", "1.2.3.4", "exa mple.com"})
    void aSiteOrSomethingThatIsNotADomainIsNotAPublicSuffix(final String host) {
        assertThat(DomainNames.isPublicSuffix(host)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3.4", "127.0.0.1", "::1", "[::1]", "2001:db8::1", "::ffff:1.2.3.4"})
    void addressLiteralIsAnIpLiteral(final String host) {
        assertThat(DomainNames.isIpLiteral(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "1.2.3.example", "localhost", "999.1.1.1", ""})
    void hostNameIsNotAnIpLiteral(final String host) {
        assertThat(DomainNames.isIpLiteral(host)).isFalse();
    }

    @Test
    void missingHostHasNoDomain() {
        assertThat(DomainNames.registrableDomain(null)).isNull();
        assertThat(DomainNames.registryDomain(null)).isNull();
    }

}
