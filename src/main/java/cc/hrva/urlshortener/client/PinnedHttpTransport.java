package cc.hrva.urlshortener.client;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

final class PinnedHttpTransport {

    private static final ExecutorService RESOLVER_THREADS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pinned-dns-", 1).factory());
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");
    private static final Pattern HEADER_VALUE = Pattern.compile("[\\t\\x20-\\x7E]*");
    private static final int MAX_CONNECTIONS = 64;
    private static final String TIMED_OUT = "timed out";

    private final Resolver resolver;
    private final Predicate<InetAddress> addressPolicy;
    private final CloseableHttpClient httpClient;

    PinnedHttpTransport(final Resolver resolver, final Predicate<InetAddress> addressPolicy, final SSLContext sslContext,
            final Duration connectTimeout) {
        this.resolver = resolver;
        this.addressPolicy = addressPolicy;
        final var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext))
                .setDnsResolver(new UnpinnedHostRefusal())
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.of(connectTimeout)).build())
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS)
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connections)
                .setConnectionReuseStrategy((request, response, context) -> false)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableDefaultUserAgent()
                .build();
    }

    Response get(final URI uri, final Map<String, String> headers, final long deadline) throws IOException, InterruptedException {
        return send(uri, new HttpUriRequestBase("GET", uri), headers, deadline);
    }

    Response head(final URI uri, final Map<String, String> headers, final long deadline) throws IOException, InterruptedException {
        return send(uri, new HttpUriRequestBase("HEAD", uri), headers, deadline);
    }

    Response post(final URI uri, final Map<String, String> headers, final byte[] body, final long deadline)
            throws IOException, InterruptedException {
        final var request = new HttpUriRequestBase("POST", uri);
        request.setEntity(new ByteArrayEntity(body, (ContentType) null));
        return send(uri, request, headers, deadline);
    }

    private Response send(final URI uri, final HttpUriRequestBase request, final Map<String, String> headers, final long deadline)
            throws IOException, InterruptedException {
        headers.forEach((name, value) -> {
            if (!HEADER_NAME.matcher(name).matches() || !HEADER_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("header " + name + " cannot be sent");
            }
            request.addHeader(name, value);
        });
        final var target = pinnedTarget(uri, deadline);
        final var remaining = remaining(deadline);
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofNanoseconds(remaining))
                .setResponseTimeout(Timeout.ofNanoseconds(remaining))
                .build());

        final var expired = new AtomicBoolean();
        final var watchdog = CompletableFuture.runAsync(() -> {
            expired.set(true);
            request.cancel();
        }, CompletableFuture.delayedExecutor(remaining, TimeUnit.NANOSECONDS));
        try {
            return new Response(httpClient.executeOpen(target, request, null), request);
        } catch (final InterruptedIOException exception) {
            throw timedOut(exception);
        } catch (final IOException exception) {
            if (expired.get()) {
                throw timedOut(exception);
            }
            throw exception;
        } finally {
            watchdog.cancel(false);
        }
    }

    private HttpHost pinnedTarget(final URI uri, final long deadline) throws IOException, InterruptedException {
        final var scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme) || StringUtils.isEmpty(uri.getHost())) {
            throw new IllegalArgumentException("not a web address");
        }
        final var host = StringUtils.removeEnd(StringUtils.removeStart(uri.getHost(), "["), "]");
        final var addresses = resolve(host, deadline);
        if (addresses.length == 0 || !Arrays.stream(addresses).allMatch(addressPolicy)) {
            throw new RefusedAddressException();
        }
        final var port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        return new HttpHost(scheme, addresses[0], host, port);
    }

    private InetAddress[] resolve(final String host, final long deadline) throws IOException, InterruptedException {
        final var lookup = CompletableFuture.supplyAsync(() -> {
            try {
                return resolver.resolve(host);
            } catch (final UnknownHostException exception) {
                return null;
            }
        }, RESOLVER_THREADS);
        final InetAddress[] addresses;
        try {
            addresses = lookup.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (final TimeoutException exception) {
            lookup.cancel(true);
            throw new HttpTimeoutException(TIMED_OUT);
        } catch (final ExecutionException exception) {
            throw new IOException("lookup failed", exception.getCause());
        }
        if (addresses == null) {
            throw new UnknownHostException(host);
        }
        return addresses;
    }

    private static long remaining(final long deadline) throws HttpTimeoutException {
        final var remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new HttpTimeoutException(TIMED_OUT);
        }
        return remaining;
    }

    private static HttpTimeoutException timedOut(final IOException cause) {
        final var timeout = new HttpTimeoutException(TIMED_OUT);
        timeout.initCause(cause);
        return timeout;
    }

    static final class RefusedAddressException extends IOException {

        private RefusedAddressException() {
            super("not a public address");
        }

    }

    private static final class UnpinnedHostRefusal implements DnsResolver {

        @Override
        public InetAddress[] resolve(final String host) throws UnknownHostException {
            throw new UnknownHostException("every connection is pinned to a checked address");
        }

        @Override
        public String resolveCanonicalHostname(final String host) throws UnknownHostException {
            throw new UnknownHostException("every connection is pinned to a checked address");
        }

    }

    static final class Response {

        private final ClassicHttpResponse response;
        private final HttpUriRequestBase request;
        private final InputStream body;

        private Response(final ClassicHttpResponse response, final HttpUriRequestBase request) throws IOException {
            this.response = response;
            this.request = request;
            final var entity = response.getEntity();
            this.body = new AbortingInputStream(entity == null ? InputStream.nullInputStream() : entity.getContent(), this);
        }

        int status() {
            return response.getCode();
        }

        Optional<String> header(final String name) {
            return Optional.ofNullable(response.getFirstHeader(name)).map(Header::getValue);
        }

        OptionalLong longHeader(final String name) {
            return header(name).filter(NumberUtils::isDigits).map(value -> OptionalLong.of(Long.parseLong(value))).orElse(OptionalLong.empty());
        }

        InputStream body() {
            return body;
        }

        void close() {
            request.cancel();
            IOUtils.closeQuietly(response);
        }

    }

    private static final class AbortingInputStream extends FilterInputStream {

        private final Response response;

        private AbortingInputStream(final InputStream content, final Response response) {
            super(content);
            this.response = response;
        }

        @Override
        public void close() {
            response.close();
        }

    }

}
