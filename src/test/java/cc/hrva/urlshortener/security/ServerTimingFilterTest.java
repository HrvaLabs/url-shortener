package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.service.stats.RecentRequests;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ServerTimingFilterTest {

    private static final String HEADER = "Server-Timing";
    private static final String FORMAT = "app;dur=\\d+\\.\\d";

    private final RecentRequests recentRequests = mock(RecentRequests.class);
    private final ServerTimingFilter filter = new ServerTimingFilter(recentRequests);
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


    @Test
    void eachRequestIsRecordedOnceWithItsStatusAndTheDurationOfTheHeader() throws ServletException, IOException {
        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).sendRedirect("https://example.com/"));

        final var duration = ArgumentCaptor.forClass(Double.class);
        verify(recentRequests).record(eq("/abc"), eq(302), duration.capture());
        assertThat(response.getHeader(HEADER)).isEqualTo(String.format(Locale.ROOT, "app;dur=%.1f", duration.getValue()));
    }

    @Test
    void aFailureThatEscapesIsRecordedAsAServerError() {
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        verify(recentRequests).record(eq("/abc"), eq(500), anyDouble());
    }

    @Test
    void aFailureAfterTheAnswerWasSentKeepsItsStatus() {
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            ((HttpServletResponse) res).setStatus(200);
            res.flushBuffer();
            throw new IOException("client went away");
        })).isInstanceOf(IOException.class);

        verify(recentRequests).record(eq("/abc"), eq(200), anyDouble());
    }

    @Test
    void anAlreadyCommittedResponseIsStillRecorded() throws ServletException, IOException {
        response.setCommitted(true);

        filter.doFilter(request, response, (req, res) -> res.getWriter().write("late"));

        verify(recentRequests).record(eq("/abc"), eq(200), anyDouble());
    }

    @Test
    void anAsyncRequestIsRecordedOnceOnItsAsyncDispatch() throws ServletException, IOException {
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> req.startAsync());

        verify(recentRequests, never()).record(anyString(), anyInt(), anyDouble());

        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(201));

        verify(recentRequests).record(eq("/abc"), eq(201), anyDouble());
    }

    @Test
    void anErrorDispatchIsNotRecordedAgain() throws ServletException, IOException {
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/abc");

        filter.doFilter(request, response, (req, res) -> res.getWriter().write("error page"));

        verify(recentRequests, never()).record(anyString(), anyInt(), anyDouble());
    }

    @Test
    void thePathIsRecordedWithoutTheContextPath() throws ServletException, IOException {
        final var withContext = new MockHttpServletRequest("GET", "/app/actuator/health");
        withContext.setContextPath("/app");

        filter.doFilter(withContext, response, new MockFilterChain());

        verify(recentRequests).record(eq("/actuator/health"), eq(200), anyDouble());
    }

}
