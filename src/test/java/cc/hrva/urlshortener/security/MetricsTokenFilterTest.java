package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsTokenFilterTest {

    private static final String TOKEN = "metrics-S3CRET-token-5b1e";

    private final AppProperties appProperties = new AppProperties();
    private final MetricsTokenFilter filter = new MetricsTokenFilter(appProperties);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    @Test
    void withoutATokenSetTheEndpointIsNotFoundWhateverIsSent() throws ServletException, IOException {
        for (final var unset : new String[]{null, "", "   "}) {
            appProperties.getMetrics().setToken(unset);
            final var sent = new MockHttpServletRequest("GET", "/actuator/prometheus");
            sent.addHeader("Authorization", "Bearer " + unset);
            final var answer = new MockHttpServletResponse();
            final var next = new MockFilterChain();

            filter.doFilter(sent, answer, next);

            assertThat(answer.getStatus()).isEqualTo(404);
            assertThat(answer.getContentAsByteArray()).isEmpty();
            assertThat(next.getRequest()).isNull();
        }
    }

    @Test
    void noTokenSentIs401WithoutABody() throws ServletException, IOException {
        appProperties.getMetrics().setToken(TOKEN);

        filter.doFilter(request, response, chain);

        assertRefused();
    }

    @Test
    void aWrongTokenIs401WithoutABody() throws ServletException, IOException {
        appProperties.getMetrics().setToken(TOKEN);
        request.addHeader("Authorization", "Bearer " + TOKEN + "x");

        filter.doFilter(request, response, chain);

        assertRefused();
    }

    @Test
    void theTokenSentAnotherWayIsRefused() throws ServletException, IOException {
        appProperties.getMetrics().setToken(TOKEN);
        request.addHeader("Authorization", "Basic " + TOKEN);
        request.addParameter("token", TOKEN);

        filter.doFilter(request, response, chain);

        assertRefused();
    }

    @Test
    void anEmptyBearerIsRefused() throws ServletException, IOException {
        appProperties.getMetrics().setToken(TOKEN);
        request.addHeader("Authorization", "Bearer ");

        filter.doFilter(request, response, chain);

        assertRefused();
    }

    @Test
    void theRightTokenPassesWithAnyCaseOfBearer() throws ServletException, IOException {
        appProperties.getMetrics().setToken(" " + TOKEN + " ");
        request.addHeader("Authorization", "bearer " + TOKEN);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    private void assertRefused() {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(chain.getRequest()).isNull();
    }

}
