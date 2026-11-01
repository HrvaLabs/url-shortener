package cc.hrva.urlshortener.controller;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LinksDownViewTest {

    private static final String FRONTEND = "https://app.hrva.cc";
    private static final String SERVER = "https://hrva.cc";
    private static final String UNREACHABLE = "hrva.cc can't reach its database right now. Try again in a minute.";

    @Test
    void aBrowserGetsASmallScriptFreePageWithRetryAfter() throws Exception {
        final var response = render(page(null), "GET", "text/html,application/xhtml+xml,*/*;q=0.8");

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(response.getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(LinkPageHeaders.CONTENT_SECURITY_POLICY);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo(LinkPageHeaders.CACHE_CONTROL);
        assertThat(response.getContentAsString())
                .contains("<p class=\"note\">hrva.cc can&#39;t open links right now. Try again in a minute.</p>")
                .contains("hrva.cc/abc")
                .contains("href=\"https://app.hrva.cc/\"")
                .doesNotContain("<script");
    }

    @Test
    void anyAcceptOrNoneGetsThePageToo() throws Exception {
        assertThat(render(page(null), "GET", null).getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(render(page(null), "GET", "*/*").getContentType()).isEqualTo("text/html;charset=UTF-8");
    }

    @Test
    void anApiClientKeepsTheProblem() throws Exception {
        final var response = render(page(null), "GET", "application/json");

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        final var body = response.getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(503);
        assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo(UNREACHABLE);
    }

    @Test
    void aHeadGetsTheStatusAndHeadersWithNoBody() throws Exception {
        final var page = render(page(null), "HEAD", "text/html");
        final var problem = render(page(null), "HEAD", "application/json");

        assertThat(page.getStatus()).isEqualTo(503);
        assertThat(page.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(page.getContentAsByteArray()).isEmpty();
        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getContentAsByteArray()).isEmpty();
    }

    @Test
    void aCustomDomainLinkShowsItsHost() throws Exception {
        assertThat(render(page("go.example.com"), "GET", "text/html").getContentAsString()).contains("go.example.com/abc");
    }

    private static LinksDownView page(final String hostname) {
        return LinksDownView.of(LinkWarningTemplates.engine(), FRONTEND, SERVER, "abc", hostname, UNREACHABLE, Duration.ofMinutes(1));
    }

    private static MockHttpServletResponse render(final LinksDownView view, final String method, final String accept) throws Exception {
        final var request = new MockHttpServletRequest(method, "/abc");
        if (accept != null) {
            request.addHeader(HttpHeaders.ACCEPT, accept);
        }
        final var response = new MockHttpServletResponse();
        view.render(Map.of(), request, response);
        return response;
    }

}
