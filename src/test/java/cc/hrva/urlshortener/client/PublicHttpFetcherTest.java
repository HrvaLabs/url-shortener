package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.PublicHttpFetcherConfiguration;
import cc.hrva.urlshortener.exception.UrlValidationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicHttpFetcherTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String WEAK_ETAG = "W/\"6ad7ef4a-499d4\"";
    private static final String LAST_MODIFIED = "Tue, 21 Oct 2026 08:00:00 GMT";

    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private final PublicHttpFetcher permissiveFetcher = new PublicHttpFetcher(url -> true);
    private ExecutorService executor;
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void productionPolicyAllowsOnlyHttpsToPublicAddresses() {
        final var policy = PublicHttpFetcher.httpsPublicOnly();

        assertThat(policy.test("https://1.1.1.1/list.txt")).isTrue();
        assertThat(policy.test("HTTPS://1.1.1.1/list.txt")).isTrue();
        assertThat(policy.test("http://1.1.1.1/list.txt")).isFalse();
        assertThat(policy.test("https://127.0.0.1/list.txt")).isFalse();
        assertThat(policy.test("https://10.1.2.3/list.txt")).isFalse();
        assertThat(policy.test("https://[::1]/list.txt")).isFalse();
        assertThat(policy.test("ftp://1.1.1.1/list.txt")).isFalse();
        assertThat(policy.test("not a url")).isFalse();
    }

    @Test
    void httpUrlIsRefusedBeforeConnecting() {
        final var fetcher = new PublicHttpFetcher(PublicHttpFetcher.httpsPublicOnly());

        assertThatThrownBy(() -> fetcher.fetch("http://1.1.1.1/list.txt", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public https address");
    }

    @Test
    void nonPublicTargetIsRefusedWithoutARequest() {
        respond("/list", exchange -> send(exchange, 200, "example.com\n"));
        final var fetcher = new PublicHttpFetcher(PublicHttpFetcher.httpsPublicOnly());

        assertThatThrownBy(() -> fetcher.fetch(baseUrl.replace("http:", "https:") + "/list", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public https address");
        assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/list", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public https address");
        assertThat(requestedPaths).isEmpty();
    }

    @Test
    void applicationFetcherUsesTheProductionPolicy() {
        respond("/list", exchange -> send(exchange, 200, "example.com\n"));
        final var fetcher = new PublicHttpFetcherConfiguration().publicHttpFetcher();

        assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/list", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public https address");
        assertThat(requestedPaths).isEmpty();
    }

    @Test
    void targetRefusedByThePolicyIsNeverRequested() {
        respond("/list", exchange -> send(exchange, 200, "example.com\n"));
        final var fetcher = new PublicHttpFetcher(url -> false);

        assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/list", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public https address");
        assertThat(requestedPaths).isEmpty();
    }

    @Test
    void everyRedirectHopIsCheckedAgainstThePolicy() {
        respond("/start", exchange -> redirect(exchange, "/private/list"));
        respond("/private/list", exchange -> send(exchange, 200, "example.com\n"));
        final Predicate<String> policy = url -> !url.contains("/private");
        final var fetcher = new PublicHttpFetcher(policy);

        assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/start", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public https address");
        assertThat(requestedPaths).containsExactly("/start");
    }

    @Test
    void moreThanThreeRedirectsAreRefused() {
        respond("/r1", exchange -> redirect(exchange, "/r2"));
        respond("/r2", exchange -> redirect(exchange, "/r3"));
        respond("/r3", exchange -> redirect(exchange, "/r4"));
        respond("/r4", exchange -> redirect(exchange, "/list"));
        respond("/list", exchange -> send(exchange, 200, "example.com\n"));

        assertThatThrownBy(() -> permissiveFetcher.fetch(baseUrl + "/r1", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("too many redirects");
        assertThat(requestedPaths).containsExactly("/r1", "/r2", "/r3", "/r4");
    }

    @Test
    void threeRedirectsAreFollowedResolvingEachLocationAgainstTheCurrentUrl() throws IOException {
        respond("/lists/a", exchange -> redirect(exchange, "b"));
        respond("/lists/b", exchange -> redirect(exchange, "/mirror/c"));
        respond("/mirror/c", exchange -> redirect(exchange, baseUrl + "/final/list.txt"));
        respond("/final/list.txt", exchange -> send(exchange, 200, "example.com\n"));

        final var result = permissiveFetcher.fetch(baseUrl + "/lists/a", 100, null, null, TIMEOUT);

        try {
            assertThat(result.notModified()).isFalse();
            assertThat(result.body()).content().isEqualTo("example.com\n");
            assertThat(requestedPaths).containsExactly("/lists/a", "/lists/b", "/mirror/c", "/final/list.txt");
        } finally {
            Files.deleteIfExists(result.body());
        }
    }

    @Test
    void notModifiedWhenTheServerMatchesTheValidatorsSentVerbatim() {
        respond("/list", exchange -> {
            final var headers = exchange.getRequestHeaders();
            if (WEAK_ETAG.equals(headers.getFirst("If-None-Match")) && LAST_MODIFIED.equals(headers.getFirst("If-Modified-Since"))) {
                exchange.getResponseHeaders().add("ETag", WEAK_ETAG);
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            send(exchange, 200, "example.com\n");
        });

        final var result = permissiveFetcher.fetch(baseUrl + "/list", 100, WEAK_ETAG, LAST_MODIFIED, TIMEOUT);

        assertThat(result.notModified()).isTrue();
        assertThat(result.body()).isNull();
        assertThat(result.etag()).isEqualTo(WEAK_ETAG);
    }

    @Test
    void bodyIsWrittenToAFileWithTheResponseValidators() throws IOException {
        respond("/list", exchange -> {
            if (exchange.getRequestHeaders().containsKey("If-None-Match")
                    || exchange.getRequestHeaders().containsKey("If-Modified-Since")) {
                send(exchange, 400, "");
                return;
            }
            exchange.getResponseHeaders().add("ETag", WEAK_ETAG);
            exchange.getResponseHeaders().add("Last-Modified", LAST_MODIFIED);
            send(exchange, 200, "example.com\nexample.net\n");
        });

        final var result = permissiveFetcher.fetch(baseUrl + "/list", 100, null, null, TIMEOUT);

        try {
            assertThat(result.notModified()).isFalse();
            assertThat(result.body()).content().isEqualTo("example.com\nexample.net\n");
            assertThat(result.etag()).isEqualTo(WEAK_ETAG);
            assertThat(result.lastModified()).isEqualTo(LAST_MODIFIED);
        } finally {
            Files.deleteIfExists(result.body());
        }
    }

    @Test
    void bodyOfExactlyMaxBytesIsAccepted() throws IOException {
        respond("/list", exchange -> sendChunked(exchange, "0123456789"));

        final var result = permissiveFetcher.fetch(baseUrl + "/list", 10, null, null, TIMEOUT);

        try {
            assertThat(result.body()).content().isEqualTo("0123456789");
        } finally {
            Files.deleteIfExists(result.body());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bodyLargerThanMaxBytesIsRefusedAndLeavesNoTempFile(final boolean chunked) throws IOException {
        respond("/list", exchange -> {
            if (chunked) {
                sendChunked(exchange, "0123456789A");
            } else {
                send(exchange, 200, "0123456789A");
            }
        });
        final var tempFilesBefore = fetchTempFiles();

        assertThatThrownBy(() -> permissiveFetcher.fetch(baseUrl + "/list", 10, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("larger than 10 bytes");
        assertThat(fetchTempFiles()).isSubsetOf(tempFilesBefore);
    }

    @Test
    void declaredLengthOverTheLimitIsRefusedBeforeReadingTheBody() {
        respond("/list", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write("0123".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            sleep(Duration.ofSeconds(3));
            exchange.close();
        });
        final var startedAt = System.nanoTime();

        assertThatThrownBy(() -> permissiveFetcher.fetch(baseUrl + "/list", 10, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("larger than 10 bytes");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void sizeLimitIsReportedInMegabytes() {
        final var body = "x".repeat(1024 * 1024 + 1);
        respond("/list", exchange -> sendChunked(exchange, body));

        assertThatThrownBy(() -> permissiveFetcher.fetch(baseUrl + "/list", 1024 * 1024, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("larger than 1 MB");
    }

    @Test
    void errorStatusIsRefusedWithTheStatusCode() {
        respond("/list", exchange -> send(exchange, 503, "<html>down</html>"));

        assertThatThrownBy(() -> permissiveFetcher.fetch(baseUrl + "/list", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("HTTP 503");
    }

    @Test
    void redirectWithoutALocationIsRefusedWithTheStatusCode() {
        respond("/list", exchange -> send(exchange, 302, ""));

        assertThatThrownBy(() -> permissiveFetcher.fetch(baseUrl + "/list", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("HTTP 302");
    }

    @Test
    void responseThatDoesNotStartWithinTheTimeoutIsRefused() {
        respond("/list", exchange -> {
            sleep(Duration.ofSeconds(3));
            send(exchange, 200, "example.com\n");
        });

        assertThatThrownBy(() -> permissiveFetcher.fetch(baseUrl + "/list", 100, null, null, Duration.ofMillis(300)))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("timed out");
    }

    @Test
    void bodyThatStallsPastTheTimeoutIsRefusedAndLeavesNoTempFile() throws IOException {
        respond("/list", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("example.com\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            sleep(Duration.ofSeconds(5));
            exchange.close();
        });
        final var tempFilesBefore = fetchTempFiles();
        final var startedAt = System.nanoTime();

        assertThatThrownBy(() -> permissiveFetcher.fetch(baseUrl + "/list", 100, null, null, Duration.ofSeconds(1)))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("timed out")
                .cause()
                .isNotInstanceOf(HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
        assertThat(fetchTempFiles()).isSubsetOf(tempFilesBefore);
    }

    @Test
    void sendsTheLinkCheckUserAgent() throws IOException {
        respond("/list", exchange -> send(exchange, 200, exchange.getRequestHeaders().getFirst("User-Agent")));

        final var result = permissiveFetcher.fetch(baseUrl + "/list", 1000, null, null, TIMEOUT);

        try {
            assertThat(result.body()).content().isEqualTo("hrva.cc-linkcheck/1.0 (+https://hrva.cc/.well-known/security.txt)");
        } finally {
            Files.deleteIfExists(result.body());
        }
    }

    private void respond(final String path, final HttpHandler handler) {
        server.createContext(path, exchange -> {
            if (!exchange.getRequestURI().getPath().equals(path)) {
                send(exchange, 404, "");
                return;
            }
            requestedPaths.add(path);
            handler.handle(exchange);
        });
    }

    private static void redirect(final HttpExchange exchange, final String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        send(exchange, 302, "");
    }

    private static void send(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (final var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendChunked(final HttpExchange exchange, final String body) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        try (final var output = exchange.getResponseBody()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void sleep(final Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Set<Path> fetchTempFiles() throws IOException {
        try (final var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(file -> file.getFileName().toString().startsWith("hrva-fetch-"))
                    .collect(Collectors.toSet());
        }
    }

}
