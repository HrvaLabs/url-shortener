package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.service.stats.RecentRequests;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.util.OnCommittedResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ServerTimingFilter extends OncePerRequestFilter {

    private static final String HEADER = "Server-Timing";
    private static final String STARTED_AT = ServerTimingFilter.class.getName() + ".STARTED_AT";
    private static final double NANOS_PER_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);

    private final RecentRequests recentRequests;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {
        final var timed = new TimedResponse(response, startedAt(request));
        var failed = false;
        try {
            chain.doFilter(request, timed);
        } catch (final Throwable throwable) {
            failed = true;
            throw throwable;
        } finally {
            if (!request.isAsyncStarted()) {
                timed.writeHeader();
                final var status = failed && !timed.isCommitted() ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : timed.getStatus();
                recentRequests.record(StringUtils.removeStart(request.getRequestURI(), request.getContextPath()), status, timed.milliseconds);
            }
        }
    }

    private static long startedAt(final HttpServletRequest request) {
        final var recorded = request.getAttribute(STARTED_AT);
        if (recorded instanceof Long nanoseconds) {
            return nanoseconds;
        }
        final var now = System.nanoTime();
        request.setAttribute(STARTED_AT, now);
        return now;
    }

    private static final class TimedResponse extends OnCommittedResponseWrapper {

        private final long startedAt;
        private boolean written;
        private double milliseconds;

        private TimedResponse(final HttpServletResponse response, final long startedAt) {
            super(response);
            this.startedAt = startedAt;
        }

        @Override
        protected void onResponseCommitted() {
            writeHeader();
        }

        private void writeHeader() {
            if (written) {
                return;
            }
            written = true;
            milliseconds = Math.max(0L, System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND;
            if (!isCommitted()) {
                setHeader(HEADER, String.format(Locale.ROOT, "app;dur=%.1f", milliseconds));
            }
        }

    }

}
