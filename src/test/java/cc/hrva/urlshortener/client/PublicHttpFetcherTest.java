package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.PublicHttpFetcherConfiguration;
import cc.hrva.urlshortener.exception.UrlValidationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
    void fourthRedirectIsRefusedEvenWhenItsLocationIsInvalid() {
        respond("/r1", exchange -> redirect(exchange, "/r2"));
        respond("/r2", exchange -> redirect(exchange, "/r3"));
        respond("/r3", exchange -> redirect(exchange, "/r4"));
        respond("/r4", exchange -> redirect(exchange, "http://[invalid"));

        assertThatThrownBy(() -> permissiveFetcher.fetch(baseUrl + "/r1", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("too many redirects");
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
    void oneDeadlineCoversEveryRedirectHop() {
        respond("/h1", exchange -> slowRedirect(exchange, "/h2"));
        respond("/h2", exchange -> slowRedirect(exchange, "/h3"));
        respond("/h3", exchange -> slowRedirect(exchange, "/list"));
        respond("/list", exchange -> send(exchange, 200, "example.com\n"));
        final var startedAt = System.nanoTime();

        assertThatThrownBy(() -> permissiveFetcher.fetch(baseUrl + "/h1", 100, null, null, Duration.ofSeconds(1)))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(requestedPaths).doesNotContain("/list");
    }

    @Test
    void deadlineSpentBeforeAHopIsReportedAsTimedOut() {
        respond("/start", exchange -> redirect(exchange, "/next"));
        respond("/next", exchange -> send(exchange, 200, "example.com\n"));
        final var fetcher = new PublicHttpFetcher(url -> {
            if (url.endsWith("/next")) {
                sleep(Duration.ofMillis(1200));
            }
            return true;
        });

        assertThatThrownBy(() -> fetcher.fetch(baseUrl + "/start", 100, null, null, Duration.ofSeconds(1)))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("timed out");
        assertThat(requestedPaths).containsExactly("/start");
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

    @Test
    void redirectPolicyAllowsHttpAndHttpsToPublicAddressesOnly() {
        final var policy = PublicHttpFetcher.httpOrHttpsPublicOnly();

        assertThat(policy.test("https://1.1.1.1/landing")).isTrue();
        assertThat(policy.test("http://1.1.1.1/landing")).isTrue();
        assertThat(policy.test("HTTP://1.1.1.1/landing")).isTrue();
        assertThat(policy.test("http://127.0.0.1/admin")).isFalse();
        assertThat(policy.test("http://10.1.2.3/admin")).isFalse();
        assertThat(policy.test("http://169.254.169.254/latest/meta-data/")).isFalse();
        assertThat(policy.test("http://[::1]/admin")).isFalse();
        assertThat(policy.test("http://[::ffff:127.0.0.1]/admin")).isFalse();
        assertThat(policy.test("ftp://1.1.1.1/landing")).isFalse();
        assertThat(policy.test("javascript:alert(1)")).isFalse();
        assertThat(policy.test("not a url")).isFalse();
    }

    @Test
    void applicationRedirectFollowerNeverRequestsAPrivateAddress() {
        respond("/start", exchange -> redirect(exchange, "/landing"));
        final var follower = new PublicHttpFetcherConfiguration().redirectFollower();

        assertThatThrownBy(() -> follower.followRedirects(baseUrl + "/start", 5, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public address");
        assertThat(requestedPaths).isEmpty();
    }

    @Test
    void aDestinationThePolicyRefusesIsAnErrorWithoutARequest() {
        respond("/start", exchange -> send(exchange, 200, ""));
        final var fetcher = new PublicHttpFetcher(url -> false);

        assertThatThrownBy(() -> fetcher.followRedirects(baseUrl + "/start", 5, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public address");
        assertThat(requestedPaths).isEmpty();
    }

    @Test
    void aLocationWithASpaceIsJudgedPercentEncoded() {
        respond("/start", exchange -> redirect(exchange, "https://evil.example/login page"));

        final var chain = loopbackOnlyFetcher().followRedirects(baseUrl + "/start", 5, TIMEOUT);

        assertThat(chain).containsExactly(baseUrl + "/start", "https://evil.example/login%20page");
        assertThat(requestedPaths).containsExactly("/start");
    }

    @Test
    void aBackslashLocationLeavesForAnotherHostLikeInABrowser() {
        respond("/start", exchange -> redirect(exchange, "/\\evil.example/"));

        final var chain = loopbackOnlyFetcher().followRedirects(baseUrl + "/start", 5, TIMEOUT);

        assertThat(chain).containsExactly(baseUrl + "/start", "http://evil.example/");
        assertThat(requestedPaths).containsExactly("/start");
    }

    @Test
    void aNonAsciiPathIsFollowedPercentEncoded() {
        respond("/start", exchange -> redirect(exchange, inUtf8("/prijava/čćž")));
        respond("/prijava/čćž", exchange -> send(exchange, 200, ""));

        final var chain = loopbackOnlyFetcher().followRedirects(baseUrl + "/start", 5, TIMEOUT);

        assertThat(chain).containsExactly(baseUrl + "/start", baseUrl + "/prijava/%C4%8D%C4%87%C5%BE");
        assertThat(requestedPaths).containsExactly("/start", "/prijava/čćž");
    }

    @Test
    void aHostThatIsPrivateOnceNormalisedIsStillRefused() {
        final var port = server.getAddress().getPort();
        respond("/start", exchange -> redirect(exchange, inUtf8("http://ｌｏｃａｌｈｏｓｔ:" + port + "/admin")));
        respond("/admin", exchange -> send(exchange, 200, ""));
        final var fetcher = new PublicHttpFetcher(url -> url.startsWith(baseUrl) || PublicHttpFetcher.httpOrHttpsPublicOnly().test(url));

        final var chain = fetcher.followRedirects(baseUrl + "/start", 5, TIMEOUT);

        assertThat(chain).containsExactly(baseUrl + "/start", "http://localhost:" + port + "/admin");
        assertThat(requestedPaths).containsExactly("/start");
    }

    @Test
    void followsARedirectChainToItsEnd() {
        respond("/go/a", exchange -> redirect(exchange, 301, "b"));
        respond("/go/b", exchange -> redirect(exchange, 302, baseUrl + "/landing"));
        respond("/landing", exchange -> send(exchange, 200, "<html>offer</html>"));

        final var chain = permissiveFetcher.followRedirects(baseUrl + "/go/a", 5, TIMEOUT);

        assertThat(chain).containsExactly(baseUrl + "/go/a", baseUrl + "/go/b", baseUrl + "/landing");
        assertThat(requestedPaths).containsExactly("/go/a", "/go/b", "/landing");
    }

    @Test
    void stopsAfterTheLastAllowedHopWithoutRequestingIt() {
        for (int hop = 0; hop < 7; hop++) {
            final var next = "/r" + (hop + 1);
            respond("/r" + hop, exchange -> redirect(exchange, next));
        }

        final var chain = permissiveFetcher.followRedirects(baseUrl + "/r0", 5, TIMEOUT);

        assertThat(chain).containsExactly(
                baseUrl + "/r0", baseUrl + "/r1", baseUrl + "/r2", baseUrl + "/r3", baseUrl + "/r4", baseUrl + "/r5");
        assertThat(requestedPaths).containsExactly("/r0", "/r1", "/r2", "/r3", "/r4");
    }

    @Test
    void stopsAtTheBudgetKeepingTheHopsLearnedSoFar() {
        respond("/h1", exchange -> {
            sleep(Duration.ofMillis(600));
            redirect(exchange, "/h2");
        });
        respond("/h2", exchange -> {
            sleep(Duration.ofMillis(600));
            redirect(exchange, "/h3");
        });
        respond("/h3", exchange -> send(exchange, 200, ""));
        final var startedAt = System.nanoTime();

        final var chain = permissiveFetcher.followRedirects(baseUrl + "/h1", 5, Duration.ofSeconds(1));

        assertThat(chain).containsExactly(baseUrl + "/h1", baseUrl + "/h2");
        assertThat(requestedPaths).containsExactly("/h1", "/h2");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aRefusedHopEndsTheChainWithoutBeingRequested() {
        respond("/start", exchange -> redirect(exchange, "/private/admin"));
        respond("/private/admin", exchange -> send(exchange, 200, ""));
        final var fetcher = new PublicHttpFetcher(url -> !url.contains("/private"));

        final var chain = fetcher.followRedirects(baseUrl + "/start", 5, TIMEOUT);

        assertThat(chain).containsExactly(baseUrl + "/start", baseUrl + "/private/admin");
        assertThat(requestedPaths).containsExactly("/start");
    }

    @Test
    void sendsTheLinkCheckUserAgentOnEveryHop() {
        final var userAgents = new CopyOnWriteArrayList<String>();
        respond("/start", exchange -> {
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            redirect(exchange, "/landing");
        });
        respond("/landing", exchange -> {
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            send(exchange, 200, "");
        });

        permissiveFetcher.followRedirects(baseUrl + "/start", 5, TIMEOUT);

        assertThat(userAgents).containsExactly(
                "hrva.cc-linkcheck/1.0 (+https://hrva.cc/.well-known/security.txt)",
                "hrva.cc-linkcheck/1.0 (+https://hrva.cc/.well-known/security.txt)");
    }

    @Test
    void neverReadsAResponseBody() {
        respond("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/landing");
            stallAfterHeaders(exchange, 302);
        });
        respond("/landing", exchange -> stallAfterHeaders(exchange, 200));
        final var startedAt = System.nanoTime();

        final var chain = permissiveFetcher.followRedirects(baseUrl + "/start", 5, TIMEOUT);

        assertThat(chain).containsExactly(baseUrl + "/start", baseUrl + "/landing");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void anAnswerThatIsNotARedirectEndsTheChain() {
        respond("/start", exchange -> redirect(exchange, "/gone"));
        respond("/gone", exchange -> send(exchange, 404, "<html>not found</html>"));

        final var chain = permissiveFetcher.followRedirects(baseUrl + "/start", 5, TIMEOUT);

        assertThat(chain).containsExactly(baseUrl + "/start", baseUrl + "/gone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://[invalid"})
    void aRedirectWithoutAUsableLocationEndsTheChain(final String location) {
        respond("/start", exchange -> {
            if (!location.isEmpty()) {
                exchange.getResponseHeaders().add("Location", location);
            }
            send(exchange, 302, "");
        });

        final var chain = permissiveFetcher.followRedirects(baseUrl + "/start", 5, TIMEOUT);

        assertThat(chain).containsExactly(baseUrl + "/start");
    }

    @Test
    void aHopThatCannotBeReachedStillEndsTheChain() throws IOException {
        final var unreachable = "http://127.0.0.1:" + closedPort() + "/landing";
        respond("/start", exchange -> redirect(exchange, unreachable));

        final var chain = permissiveFetcher.followRedirects(baseUrl + "/start", 5, TIMEOUT);

        assertThat(chain).containsExactly(baseUrl + "/start", unreachable);
    }

    @Test
    void aFirstRequestThatFailsIsAnError() throws IOException {
        respond("/slow", exchange -> {
            sleep(Duration.ofSeconds(3));
            send(exchange, 200, "");
        });

        assertThatThrownBy(() -> permissiveFetcher.followRedirects(baseUrl + "/slow", 5, Duration.ofMillis(300)))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("timed out");
        assertThatThrownBy(() -> permissiveFetcher.followRedirects("http://127.0.0.1:" + closedPort() + "/", 5, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("connection failed");
    }

    private PublicHttpFetcher loopbackOnlyFetcher() {
        return new PublicHttpFetcher(url -> url.startsWith(baseUrl));
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
        redirect(exchange, 302, location);
    }

    private static void redirect(final HttpExchange exchange, final int status, final String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        send(exchange, status, "");
    }

    private static String inUtf8(final String headerValue) {
        return new String(headerValue.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static void stallAfterHeaders(final HttpExchange exchange, final int status) throws IOException {
        exchange.sendResponseHeaders(status, 1_000_000);
        exchange.getResponseBody().write("<html>".getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        sleep(Duration.ofSeconds(3));
        exchange.close();
    }

    private static int closedPort() throws IOException {
        try (final var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void slowRedirect(final HttpExchange exchange, final String location) throws IOException {
        sleep(Duration.ofMillis(400));
        redirect(exchange, location);
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
