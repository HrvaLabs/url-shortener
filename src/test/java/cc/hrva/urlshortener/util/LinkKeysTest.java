package cc.hrva.urlshortener.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkKeysTest {

    @Test
    void keysDropSchemeDefaultPortAndFragmentButKeepPathAndQueryCase() {
        assertThat(LinkKeys.of("HTTPS://Ex.COM:443/A/b?q=1#f")).containsExactly("ex.com/A/b?q=1", "ex.com/A/b");
    }

    @Test
    void emptyPathBecomesSlash() {
        assertThat(LinkKeys.of("http://ex.com")).containsExactly("ex.com/");
    }

    @Test
    void ipv4HostKeepsANonDefaultPort() {
        assertThat(LinkKeys.of("http://1.2.3.4:8080/i")).containsExactly("1.2.3.4:8080/i");
    }

    @Test
    void internationalHostNormalisesLikeHostOf() {
        final var host = DomainNames.hostOf("https://Bücher.de/Pfad").orElseThrow();

        assertThat(LinkKeys.of("https://Bücher.de/Pfad")).containsExactly(host + "/Pfad");
        assertThat(host).isEqualTo("xn--bcher-kva.de");
    }

    @Test
    void hostLosesItsTrailingDotAndUserInfo() {
        assertThat(LinkKeys.of("https://paypal.com:pw@Evil.Example./login")).containsExactly("evil.example/login");
    }

    @Test
    void httpAndHttpsGiveTheSameKey() {
        assertThat(LinkKeys.of("http://ex.com/a")).containsExactly("ex.com/a");
        assertThat(LinkKeys.of("https://ex.com/a")).containsExactly("ex.com/a");
    }

    @Test
    void portIsDroppedOnlyWhenItIsTheDefaultForTheScheme() {
        assertThat(LinkKeys.of("http://ex.com:80/a")).containsExactly("ex.com/a");
        assertThat(LinkKeys.of("http://ex.com:443/a")).containsExactly("ex.com:443/a");
        assertThat(LinkKeys.of("https://ex.com:80/a")).containsExactly("ex.com:80/a");
        assertThat(LinkKeys.of("https://ex.com:/a")).containsExactly("ex.com/a");
    }

    @Test
    void queryWithoutPathKeysTheRoot() {
        assertThat(LinkKeys.of("http://ex.com?q=1")).containsExactly("ex.com/?q=1", "ex.com/");
    }

    @Test
    void emptyQueryCountsAsNoQuery() {
        assertThat(LinkKeys.of("https://ex.com/a?")).containsExactly("ex.com/a");
    }

    @Test
    void ipv6HostKeepsItsBrackets() {
        assertThat(LinkKeys.of("http://[2001:DB8::1]:8080/x")).containsExactly("[2001:db8::1]:8080/x");
        assertThat(LinkKeys.of("https://[::1]")).containsExactly("[::1]/");
    }

    @Test
    void encodedAndNonAsciiPathsAreKeptAsWritten() {
        assertThat(LinkKeys.of("https://ex.com/a%20b/čaj")).containsExactly("ex.com/a%20b/čaj");
    }

    @Test
    void unparseableInputGivesNoKeys() {
        assertThat(LinkKeys.of(null)).isEmpty();
        assertThat(LinkKeys.of("")).isEmpty();
        assertThat(LinkKeys.of("not a url")).isEmpty();
        assertThat(LinkKeys.of("ex.com/a")).isEmpty();
        assertThat(LinkKeys.of("ftp://ex.com/a")).isEmpty();
        assertThat(LinkKeys.of("https://")).isEmpty();
        assertThat(LinkKeys.of("https://ex.com:abc/")).isEmpty();
        assertThat(LinkKeys.of("https://ex.com/a b")).isEmpty();
        assertThat(LinkKeys.of("https://[zz/")).isEmpty();
    }

}
