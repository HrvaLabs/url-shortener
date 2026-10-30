package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.util.RedirectLocations;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.HtmlUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LinkWarningViewTest {

    private static final String FRONTEND = "https://hrva.cc";
    private static final String DESTINATION = "https://xn--e1afmkfd.example/p?a=\"<x>'&b";
    private static final Pattern CONTINUE_HREF = Pattern.compile("<a href=\"([^\"]*)\" rel=\"nofollow\"");

    @Test
    void theWarningPageEscapesTheDestination() throws Exception {
        final var location = RedirectLocations.normalized(DESTINATION);

        final var body = render(page(DESTINATION, location), "GET").getContentAsString();

        assertThat(body).contains("&quot;&lt;x&gt;").contains("&amp;").doesNotContain("\"<x>'");
        final var href = CONTINUE_HREF.matcher(body);
        assertThat(href.find()).isTrue();
        assertThat(HtmlUtils.htmlUnescape(href.group(1))).isEqualTo(location);
        assertThat(body).contains(">Continue to xn--e1afmkfd.example</a>");
    }

    @Test
    void theHeadersAreExact() throws Exception {
        final var response = render(page(DESTINATION, RedirectLocations.normalized(DESTINATION)), "GET");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store, no-transform");
        assertThat(response.getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        assertThat(response.getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(response.getCookies()).isEmpty();
        assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
    }

    @Test
    void headHasNoBody() throws Exception {
        final var get = render(page(DESTINATION, RedirectLocations.normalized(DESTINATION)), "GET");
        final var head = render(page(DESTINATION, RedirectLocations.normalized(DESTINATION)), "HEAD");

        assertThat(head.getContentAsByteArray()).isEmpty();
        assertThat(head.getStatus()).isEqualTo(200);
        assertThat(head.getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(head.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store, no-transform");
        assertThat(head.getHeader("Content-Security-Policy")).isEqualTo(get.getHeader("Content-Security-Policy"));
        assertThat(head.getContentLength()).isEqualTo(get.getContentAsByteArray().length);
    }

    @Test
    void thePageHasTheExactCopyAndLinks() throws Exception {
        final var body = render(page("https://xdvqnxj.com/login", "https://xdvqnxj.com/login"), "GET").getContentAsString();

        assertThat(body)
                .contains("<title>This link was made without an account &middot; hrva.cc</title>")
                .contains("<h1>This link was made without an account. It goes to:</h1>")
                .contains("<p class=\"destination\" style=\"overflow-wrap:anywhere\">https://xdvqnxj.com/login</p>")
                .contains("<p class=\"note\">The site is new or its name looks machine-made. Continue only if this is the address you expected.</p>")
                .contains("word-break: break-all;")
                .doesNotContain("letters outside A to Z")
                .contains("<a href=\"https://xdvqnxj.com/login\" rel=\"nofollow\" class=\"continue\">Continue to xdvqnxj.com</a>")
                .contains("<a href=\"https://hrva.cc/report?s=abc\" class=\"report\">Report this link</a>")
                .contains("<a href=\"https://hrva.cc\" class=\"home\">hrva.cc</a>")
                .contains("@media (prefers-color-scheme: dark)");
    }

    @Test
    void thePageHasNoScriptAndNoExternalResource() throws Exception {
        final var body = render(page("https://xdvqnxj.com/login", "https://xdvqnxj.com/login"), "GET").getContentAsString();

        assertThat(body.toLowerCase(java.util.Locale.ROOT)).doesNotContain("<script", "<link", "<img", "<iframe", "src=", "@import", "url(", "onload");
    }

    @Test
    void theStoredDestinationIsShownWholeAndTheButtonNamesItsHost() throws Exception {
        final var stored = "https://čokolada.hr/" + "a".repeat(2000);
        final var location = RedirectLocations.normalized(stored);

        final var body = render(page(stored, location), "GET").getContentAsString();

        assertThat(body).contains(">" + stored + "</p>").contains(">Continue to xn--okolada-i6a.hr</a>").doesNotContain(Character.toString(0x2026));
    }

    @Test
    void aUnicodeHostNamesItsPlainSpelling() throws Exception {
        final var stored = "https://čokolada.hr/menu2";

        final var body = render(page(stored, RedirectLocations.normalized(stored)), "GET").getContentAsString();

        assertThat(body).contains("<p class=\"note\">This address uses letters outside A to Z. Its plain spelling is xn--okolada-i6a.hr.</p>");
    }

    @Test
    void aPunycodeHostAsTypedNeedsNoPlainSpelling() throws Exception {
        final var stored = "https://xn--okolada-i6a.hr/menu2";

        final var body = render(page(stored, stored), "GET").getContentAsString();

        assertThat(body).doesNotContain("letters outside A to Z");
    }

    @Test
    void aHostThatCannotBeWrittenInAsciiIsNamedAsStored() throws Exception {
        final var stored = "https://" + "č".repeat(64) + ".hr/torta";

        final var body = render(page(stored, stored), "GET").getContentAsString();

        assertThat(body).contains(">Continue to " + "č".repeat(64) + ".hr</a>").doesNotContain("letters outside A to Z");
    }

    @Test
    void theAliasIsEncodedInTheReportLink() throws Exception {
        final var view = new LinkWarningView(LinkWarningTemplates.engine(), FRONTEND, "a b&c", "https://xdvqnxj.com/", "https://xdvqnxj.com/");

        assertThat(render(view, "GET").getContentAsString()).contains("href=\"https://hrva.cc/report?s=a+b%26c\"");
    }

    private static LinkWarningView page(final String destination, final String location) {
        return new LinkWarningView(LinkWarningTemplates.engine(), FRONTEND, "abc", destination, location);
    }

    private static MockHttpServletResponse render(final LinkWarningView view, final String method) throws Exception {
        final var request = new MockHttpServletRequest(method, "/abc");
        final var response = new MockHttpServletResponse();
        view.render(Map.of(), request, response);
        return response;
    }

}
