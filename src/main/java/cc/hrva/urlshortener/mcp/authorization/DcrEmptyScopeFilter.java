package cc.hrva.urlshortener.mcp.authorization;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@RequiredArgsConstructor
public class DcrEmptyScopeFilter extends OncePerRequestFilter {

    private static final String SCOPE = "scope";

    private final JsonMapper jsonMapper;

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {
        final var body = request.getInputStream().readAllBytes();
        filterChain.doFilter(new BodyReplacingRequest(request, withoutBlankScope(body)), response);
    }

    private byte[] withoutBlankScope(final byte[] body) {
        try {
            if (jsonMapper.readTree(body) instanceof ObjectNode registration && isBlank(registration.get(SCOPE))) {
                registration.remove(SCOPE);
                return jsonMapper.writeValueAsBytes(registration);
            }
        } catch (final JacksonException exception) {
            return body;
        }

        return body;
    }

    private static boolean isBlank(final JsonNode scope) {
        return scope != null && (scope.isNull() || scope.isString() && scope.asString().isBlank());
    }

    private static final class BodyReplacingRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BodyReplacingRequest(final HttpServletRequest request, final byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            final var inputStream = new ByteArrayInputStream(body);

            return new ServletInputStream() {

                @Override
                public int read() {
                    return inputStream.read();
                }

                @Override
                public int read(final byte[] buffer, final int offset, final int length) {
                    return inputStream.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(final ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

            };
        }

        @Override
        public BufferedReader getReader() {
            final var encoding = getCharacterEncoding();
            final var charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;

            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

    }

}
