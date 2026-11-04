package cc.hrva.urlshortener.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerTimingFilterTest {

    private static final String HEADER = "Server-Timing";
    private static final String FORMAT = "app;dur=\\d+\\.\\d";

    private final ServerTimingFilter filter = new ServerTimingFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/abc");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void runsBeforeEveryOtherFilter() {
        assertThat(ServerTimingFilter.class.getAnnotation(Order.class).value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void aRedirectCarriesTheHeader() throws ServletException, IOException {
        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).sendRedirect("https://example.com/"));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("https://example.com/");
        assertThat(response.getHeaders(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void aStatusAndHeadersSetWithoutAnyWriteStillCarryTheHeader() throws ServletException, IOException {
        filter.doFilter(request, response, (req, res) -> {
            final var http = (HttpServletResponse) res;
            http.setStatus(302);
            http.setHeader("Location", "https://example.com/");
        });

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeaders(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void aBodyThatIsNeverFlushedStillCarriesTheHeader() throws ServletException, IOException {
        filter.doFilter(request, response, (req, res) -> res.getWriter().write("{\"a\":1}"));

        assertThat(response.getContentAsString()).isEqualTo("{\"a\":1}");
        assertThat(response.getHeaders(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void aStreamedBodyThatIsFlushedCarriesTheHeaderOnce() throws ServletException, IOException {
        filter.doFilter(request, response, (req, res) -> {
            res.getOutputStream().write("data".getBytes());
            res.flushBuffer();
            res.getOutputStream().write("more".getBytes());
            res.flushBuffer();
        });

        assertThat(response.getContentAsString()).isEqualTo("datamore");
        assertThat(response.getHeaders(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void aSentErrorCarriesTheHeader() throws ServletException, IOException {
        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).sendError(404));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getHeaders(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void anUnauthorizedAnswerCarriesTheHeader() throws ServletException, IOException {
        filter.doFilter(request, response, (req, res) -> {
            final var http = (HttpServletResponse) res;
            http.setStatus(401);
            http.setHeader("WWW-Authenticate", "Bearer");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getHeaders(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void aHeaderAlreadySetByAnotherLayerIsReplacedNotRepeated() throws ServletException, IOException {
        filter.doFilter(request, response, (req, res) -> {
            ((HttpServletResponse) res).setHeader(HEADER, "other;dur=1");
            res.flushBuffer();
        });

        assertThat(response.getHeaders(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void aResponseThatIsAlreadyCommittedIsLeftAlone() throws ServletException, IOException {
        response.setCommitted(true);

        filter.doFilter(request, response, (req, res) -> res.getWriter().write("late"));

        assertThat(response.containsHeader(HEADER)).isFalse();
        assertThat(response.getContentAsString()).isEqualTo("late");
    }

    @Test
    void aFailureStillPropagatesAndTheHeaderIsAdded() {
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class).hasMessage("boom");

        assertThat(response.getHeaders(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void theDurationIsNeverNegative() throws ServletException, IOException {
        filter.doFilter(request, response, new MockFilterChain());

        final var duration = Double.parseDouble(response.getHeader(HEADER).substring("app;dur=".length()));
        assertThat(duration).isNotNegative();
    }

    @Test
    void aRequestThatWentAsyncGetsTheHeaderOnItsAsyncDispatch() throws ServletException, IOException {
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> req.startAsync());

        assertThat(response.containsHeader(HEADER)).isFalse();

        final var dispatched = new MockHttpServletResponse();
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, dispatched, (req, res) -> res.getWriter().write("done"));

        assertThat(dispatched.getHeaders(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void anErrorDispatchIsLeftAlone() throws ServletException, IOException {
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/abc");

        filter.doFilter(request, response, (req, res) -> res.getWriter().write("error page"));

        assertThat(response.containsHeader(HEADER)).isFalse();
    }

}
