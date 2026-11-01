package cc.hrva.urlshortener.controller;

import com.jayway.jsonpath.JsonPath;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LinkUnavailableViewTest {

    private static final String FRONTEND = "https://app.hrva.cc";
    private static final String SERVER = "https://hrva.cc";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "missing|404|There's no hrva.cc link with this name.",
            "blocked|410|hrva.cc disabled this link because it led to a site reported for phishing, malware or scams.",
            "expired|410|This link has expired.",
            "limit|410|This link has reached its visit limit.",
            "inactive|410|Its owner has turned this link off.",
            "domain|410|This link's domain is turned off right now."})
    void eachReasonHasItsStatusAndTheFrontendText(final String reason, final int status, final String text) throws Exception {
        final var response = render(page("abc", null, reason), "GET", "text/html");

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString()).contains("<p class=\"note\">" + text.replace("'", "&#39;") + "</p>");
    }

    @Test
    void anUnknownLinkOnACustomDomainNamesThatDomain() throws Exception {
        final var body = render(page("menu", "go.example.com", "missing"), "GET", "text/html").getContentAsString();

        assertThat(body)
                .contains("There&#39;s no go.example.com link with this name.")
                .contains("<p class=\"destination\">go.example.com/menu</p>")
                .contains("href=\"https://app.hrva.cc/report?s=go.example.com%2Fmenu\"");
    }

    @Test
    void thePageHasTheExactCopyAndLinks() throws Exception {
        final var body = render(page("abc", null, "expired"), "GET", "text/html").getContentAsString();

        assertThat(body)
                .contains("<title>This link isn&#39;t available &middot; hrva.cc</title>")
                .contains("<h1>This link isn&#39;t available</h1>")
                .contains("<p class=\"destination\">hrva.cc/abc</p>")
                .contains("<a href=\"https://app.hrva.cc/report?s=abc\" class=\"report\">Report a link</a>")
                .contains("<a href=\"https://app.hrva.cc/\" class=\"continue\">Go to hrva.cc</a>")
                .contains("<a href=\"https://app.hrva.cc\" class=\"home\">hrva.cc</a>")
                .contains("@media (prefers-color-scheme: dark)");
    }

    @Test
    void theHeadersMatchTheWarningPage() throws Exception {
        final var response = render(page("abc", null, "blocked"), "GET", "text/html");

        assertThat(response.getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo(LinkPageHeaders.CACHE_CONTROL);
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(LinkPageHeaders.CONTENT_SECURITY_POLICY);
        assertThat(response.getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getCookies()).isEmpty();
        assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
    }

    @Test
    void thePageHasNoScriptAndNoExternalResource() throws Exception {
        final var body = render(page("abc", null, "blocked"), "GET", "text/html").getContentAsString();

        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("<script", "<link", "<img", "<iframe", "src=", "@import", "url(", "onload");
    }

    @Test
    void theAliasIsEscapedAndCutTo32Characters() throws Exception {
        final var body = render(page("<b>" + "a".repeat(40), null, "missing"), "GET", "text/html").getContentAsString();

        assertThat(body).contains("hrva.cc/&lt;b&gt;" + "a".repeat(29) + "</p>").doesNotContain("<b>a");
    }

    @Test
    void headHasTheSameStatusAndHeadersButNoBody() throws Exception {
        final var get = render(page("abc", null, "limit"), "GET", "text/html");
        final var head = render(page("abc", null, "limit"), "HEAD", "text/html");

        assertThat(head.getStatus()).isEqualTo(410);
        assertThat(head.getContentAsByteArray()).isEmpty();
        assertThat(head.getContentLength()).isEqualTo(get.getContentAsByteArray().length);
        assertThat(head.getHeader("Content-Security-Policy")).isEqualTo(get.getHeader("Content-Security-Policy"));
    }

    @Test
    void aJsonClientGetsAProblemWithTheReason() throws Exception {
        final var response = render(page("abc", null, "expired"), "GET", "application/json");

        assertThat(response.getStatus()).isEqualTo(410);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo(LinkPageHeaders.CACHE_CONTROL);
        final var body = response.getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.title")).isEqualTo("Gone");
        assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo("This link has expired.");
        assertThat(JsonPath.<String>read(body, "$.reason")).isEqualTo("expired");
        assertThat(JsonPath.<String>read(body, "$.instance")).isEqualTo("/abc");
    }

    @Test
    void aJsonHeadHasNoBody() throws Exception {
        final var response = render(page("nope", null, "missing"), "HEAD", "application/problem+json");

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void aBrowserThatAlsoAcceptsJsonGetsThePage() throws Exception {
        final var response = render(page("abc", null, "missing"), "GET", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");

        assertThat(response.getContentType()).isEqualTo("text/html;charset=UTF-8");
    }

    @Test
    void anyOtherClientGetsThePage() throws Exception {
        assertThat(render(page("abc", null, "missing"), "GET", "*/*").getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(render(page("abc", null, "missing"), "GET", null).getContentType()).isEqualTo("text/html;charset=UTF-8");
    }

    private static LinkUnavailableView page(final String alias, final String hostname, final String reason) {
        return LinkUnavailableView.of(LinkWarningTemplates.engine(), FRONTEND, SERVER, alias, hostname, reason);
    }

    private static MockHttpServletResponse render(final LinkUnavailableView view, final String method, final String accept) throws Exception {
        final var request = new MockHttpServletRequest(method, "/abc");
        if (accept != null) {
            request.addHeader(HttpHeaders.ACCEPT, accept);
        }
        final var response = new MockHttpServletResponse();
        view.render(Map.of(), request, response);
        return response;
    }

}
