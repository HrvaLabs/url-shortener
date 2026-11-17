package cc.hrva.urlshortener.mcp;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import lombok.RequiredArgsConstructor;

final class ReplayableRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final String protocolVersion;

    ReplayableRequest(final HttpServletRequest request, final byte[] body) {
        this(request, body, null);
    }

    ReplayableRequest(final HttpServletRequest request, final byte[] body, final String protocolVersion) {
        super(request);
        this.body = body;
        this.protocolVersion = protocolVersion;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new BodyInputStream(new ByteArrayInputStream(body));
    }

    @Override
    public BufferedReader getReader() {
        final var encoding = getCharacterEncoding();
        final var charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;

        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public String getHeader(final String name) {
        return replacesHeader(name) ? protocolVersion : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(final String name) {
        return replacesHeader(name) ? Collections.enumeration(List.of(protocolVersion)) : super.getHeaders(name);
    }

    private boolean replacesHeader(final String name) {
        return protocolVersion != null && McpEraFilter.PROTOCOL_VERSION_HEADER.equalsIgnoreCase(name);
    }

    @RequiredArgsConstructor
    private static final class BodyInputStream extends ServletInputStream {

        private final ByteArrayInputStream body;

        @Override
        public int read() {
            return body.read();
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) {
            return body.read(buffer, offset, length);
        }

        @Override
        public int available() {
            return body.available();
        }

        @Override
        public boolean isFinished() {
            return body.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            throw new UnsupportedOperationException();
        }

    }

}
