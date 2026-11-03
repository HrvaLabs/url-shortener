package cc.hrva.urlshortener.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.util.OnCommittedResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ServerTimingFilter extends OncePerRequestFilter {

    private static final String HEADER = "Server-Timing";
    private static final String STARTED_AT = ServerTimingFilter.class.getName() + ".STARTED_AT";
    private static final double NANOS_PER_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {
        final var timed = new TimedResponse(response, startedAt(request));
        try {
            chain.doFilter(request, timed);
        } finally {
            if (!request.isAsyncStarted()) {
                timed.writeHeader();
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

        private TimedResponse(final HttpServletResponse response, final long startedAt) {
            super(response);
            this.startedAt = startedAt;
        }

        @Override
        protected void onResponseCommitted() {
            writeHeader();
        }

        private void writeHeader() {
            if (written || isCommitted()) {
                written = true;
                return;
            }
            written = true;
            final var milliseconds = Math.max(0L, System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND;
            setHeader(HEADER, String.format(Locale.ROOT, "app;dur=%.1f", milliseconds));
        }

    }

}
