package cc.hrva.urlshortener.mcp;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.DcrEmptyScopeFilter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRequestBodyLimitFilterTest {

    private static final int LIMIT = 64 * 1024;
    private static final String REQUEST_TOO_LARGE = "{\"error\":\"request_too_large\"}";

    private final McpRequestBodyLimitFilter filter = new McpRequestBodyLimitFilter();

    @Test
    void oversizedContentLengthIs413BeforeTheBodyIsRead() throws Exception {
        final var request = new MockHttpServletRequest("POST", "/mcp/anon");
        request.setContent(new byte[LIMIT + 1]);
        final var response = new MockHttpServletResponse();
        final var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertTooLarge(response);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void endlessBodyOfUnknownLengthIs413AfterAtMostTheLimit() throws Exception {
        final var body = new EndlessBody();
        final var response = new MockHttpServletResponse();
        final var registrationFilter = new DcrEmptyScopeFilter(JsonMapper.builder().build());

        filter.doFilter(unknownLength("/oauth2/register", body), response,
                (request, filteredResponse) -> registrationFilter.doFilter(request, filteredResponse, new MockFilterChain()));

        assertTooLarge(response);
        assertThat(body.bytesRead()).isLessThanOrEqualTo(LIMIT + 1);
    }

    @Test
    void readingPastTheLimitFailsForStreamsAndReaders() throws Exception {
        final var streamBody = new EndlessBody();
        final var readerBody = new EndlessBody();

        filter.doFilter(unknownLength("/mcp", streamBody), new MockHttpServletResponse(), (request, response) -> {
            assertThatThrownBy(() -> request.getInputStream().readAllBytes()).isInstanceOf(RequestBodyTooLargeException.class);
            assertThatThrownBy(() -> request.getInputStream().read()).isInstanceOf(RequestBodyTooLargeException.class);
        });
        filter.doFilter(unknownLength("/mcp/anon", readerBody), new MockHttpServletResponse(), (request, response) ->
                assertThatThrownBy(() -> request.getReader().transferTo(Writer.nullWriter())).isInstanceOf(RequestBodyTooLargeException.class));

        assertThat(streamBody.bytesRead()).isLessThanOrEqualTo(LIMIT + 1);
        assertThat(readerBody.bytesRead()).isLessThanOrEqualTo(LIMIT + 1);
    }

    @Test
    void bodiesUpToTheLimitPassThroughUnchanged() throws Exception {
        final var content = new byte[LIMIT];
        Arrays.fill(content, (byte) ' ');
        final var knownLength = new MockHttpServletRequest("POST", "/mcp");
        knownLength.setContent(content);
        final var unknownLength = unknownLength("/mcp", new DelegatingServletInputStream(new ByteArrayInputStream(content)));

        for (final var request : List.of(knownLength, unknownLength)) {
            final var response = new MockHttpServletResponse();
            final var seen = new AtomicReference<byte[]>();

            filter.doFilter(request, response, (filteredRequest, filteredResponse) -> seen.set(filteredRequest.getInputStream().readAllBytes()));

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(seen.get()).isEqualTo(content);
        }
    }

    @Test
    void registrationFilterNeverBuffersMoreThanTheLimitOnItsOwn() {
        final var body = new EndlessBody();
        final var registrationFilter = new DcrEmptyScopeFilter(JsonMapper.builder().build());

        assertThatThrownBy(() -> registrationFilter.doFilter(unknownLength("/oauth2/register", body), new MockHttpServletResponse(), new MockFilterChain()))
                .isInstanceOf(RequestBodyTooLargeException.class);
        assertThat(body.bytesRead()).isLessThanOrEqualTo(LIMIT + 1);
    }

    @Test
    void onlyPostIsLimited() throws Exception {
        final var request = new MockHttpServletRequest("GET", "/mcp");
        request.setContent(new byte[LIMIT + 1]);
        final var response = new MockHttpServletResponse();
        final var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void runsBeforeTheRegistrationFilterAndSpringSecurity() {
        final var configuration = new AuthorizationServerConfiguration(new AppProperties(), new MockEnvironment());
        final var bodyLimit = configuration.mcpRequestBodyLimitFilterRegistration();
        final var registration = configuration.dcrEmptyScopeFilterRegistration(JsonMapper.builder().build());

        assertThat(bodyLimit.getUrlPatterns()).containsExactlyInAnyOrder("/oauth2/register", "/mcp", "/mcp/anon");
        assertThat(bodyLimit.getOrder()).isLessThan(registration.getOrder());
        assertThat(registration.getOrder()).isLessThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
    }

    private static void assertTooLarge(final MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentAsString()).isEqualTo(REQUEST_TOO_LARGE);
    }

    private static MockHttpServletRequest unknownLength(final String path, final ServletInputStream body) {
        return new MockHttpServletRequest("POST", path) {

            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                return body;
            }

        };
    }

    private static final class EndlessBody extends ServletInputStream {

        private long bytesRead;

        @Override
        public int read() {
            bytesRead++;
            return ' ';
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) {
            Arrays.fill(buffer, offset, offset + length, (byte) ' ');
            bytesRead += length;
            return length;
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            throw new UnsupportedOperationException();
        }

        private long bytesRead() {
            return bytesRead;
        }

    }

}
