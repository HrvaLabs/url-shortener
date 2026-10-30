package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.Url;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortLinksTest {

    @Test
    void hrvaFormsAreUnchanged() {
        assertThat(shortLinks("https://hrva.cc").url(null, "abc")).isEqualTo("https://hrva.cc/abc");
        assertThat(shortLinks("https://hrva.cc/").url(null, "abc")).isEqualTo("https://hrva.cc/abc");
        assertThat(shortLinks("https://hrva.cc").display(null, "abc")).isEqualTo("hrva.cc/abc");
        assertThat(shortLinks("https://hrva.cc/").display(null, "abc")).isEqualTo("hrva.cc/abc");
        assertThat(shortLinks("hrva.cc").display(null, "abc")).isEqualTo("hrva.cc/abc");
        assertThat(shortLinks("http://localhost:18087").url(null, "abc")).isEqualTo("http://localhost:18087/abc");
        assertThat(shortLinks("http://localhost:18087").display(null, "abc")).isEqualTo("localhost:18087/abc");
        assertThat(shortLinks(null).url(null, "abc")).isEqualTo("/abc");
        assertThat(shortLinks(null).display(null, "abc")).isEqualTo("/abc");
        assertThat(shortLinks("https://hrva.cc").host(null)).isEqualTo("hrva.cc");
        assertThat(shortLinks("https://hrva.cc").url(" ", "abc")).isEqualTo("https://hrva.cc/abc");
        assertThat(shortLinks("https://hrva.cc").display("", "abc")).isEqualTo("hrva.cc/abc");
    }

    @Test
    void customFormsUseTheHost() {
        final var shortLinks = shortLinks("https://hrva.cc");

        assertThat(shortLinks.url("go.example.com", "menu")).isEqualTo("https://go.example.com/menu");
        assertThat(shortLinks.display("go.example.com", "menu")).isEqualTo("go.example.com/menu");
        assertThat(shortLinks.host("go.example.com")).isEqualTo("go.example.com");
        assertThat(shortLinks.display("xn--80ak6aa92e.example.com", "menu")).isEqualTo("xn--80ak6aa92e.example.com/menu");
    }

    @Test
    void aLinksHostnameIsItsDomainsOrNullForHrva() {
        final var domain = CustomDomain.builder().id(7L).hostname("go.example.com").build();

        assertThat(ShortLinks.hostnameOf(Url.builder().shortUrl("menu").domain(domain).build())).isEqualTo("go.example.com");
        assertThat(ShortLinks.hostnameOf(Url.builder().shortUrl("menu").build())).isNull();
    }

    private static ShortLinks shortLinks(final String serverUrl) {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl(serverUrl);
        return new ShortLinks(appProperties);
    }

}
