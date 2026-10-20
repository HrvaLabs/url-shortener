package cc.hrva.urlshortener.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
public class McpRequestBodyLimitFilter extends OncePerRequestFilter {

    public static final int MAX_BODY_BYTES = 64 * 1024;

    private static final String REQUEST_TOO_LARGE = """
            {"error":"request_too_large"}""";
    private static final String NO_STORE = "no-store";

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            rejectTooLarge(response);
            return;
        }

        try {
            filterChain.doFilter(new BoundedBodyRequest(request), response);
        } catch (final RequestBodyTooLargeException exception) {
            if (response.isCommitted()) {
                throw exception;
            }
            response.reset();
            rejectTooLarge(response);
        }
    }

    private static void rejectTooLarge(final HttpServletResponse response) throws IOException {
        log.info("Rejected a request body over the size limit");
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(REQUEST_TOO_LARGE);
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private ServletInputStream inputStream;
        private BufferedReader reader;

        private BoundedBodyRequest(final HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new BoundedInputStream(super.getInputStream());
            }

            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                final var encoding = getCharacterEncoding();
                final var charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }

            return reader;
        }

    }

    @RequiredArgsConstructor
    private static final class BoundedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long bytesRead;

        @Override
        public int read() throws IOException {
            refuseOnceOverTheLimit();
            final var next = delegate.read();
            if (next != -1) {
                count(1);
            }

            return next;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            refuseOnceOverTheLimit();
            final var read = delegate.read(buffer, offset, (int) Math.min(length, MAX_BODY_BYTES + 1L - bytesRead));
            if (read > 0) {
                count(read);
            }

            return read;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        private void count(final int read) throws RequestBodyTooLargeException {
            bytesRead += read;
            refuseOnceOverTheLimit();
        }

        private void refuseOnceOverTheLimit() throws RequestBodyTooLargeException {
            if (bytesRead > MAX_BODY_BYTES) {
                throw new RequestBodyTooLargeException();
            }
        }

    }

}
