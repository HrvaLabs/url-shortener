package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.PublicHttpFetcherConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertificateException;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PublicHttpFetcherProbeTest {

    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final byte[] PUBLIC_ADDRESS = {93, (byte) 184, (byte) 215, 14};
    private static final byte[] PRIVATE_ADDRESS = {10, 0, 0, 5};

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> rangeHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> userAgents = new ConcurrentHashMap<>();
    private final Resolver publicResolver = host -> new InetAddress[] {InetAddress.getByAddress(host, PUBLIC_ADDRESS)};

    @TempDir
    private Path keys;
    private ExecutorService executor;
    private HttpServer server;
    private String baseUrl;
    private PublicHttpFetcher fetcher;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        fetcher = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), publicResolver);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void okIsAlive() {
        answer("/ok", 200, 200);

        assertThat(fetcher.probe(baseUrl + "/ok", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.ALIVE, "ok", 200));
        assertThat(requests).containsExactly("HEAD /ok");
    }

    @Test
    void notModifiedIsAlive() {
        answer("/cached", 304, 304);

        assertThat(fetcher.probe(baseUrl + "/cached", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.ALIVE, "ok", 304));
    }

    @Test
    void headNotFoundIsConfirmedWithGet() {
        answer("/head-only", 404, 200);
        answer("/gone-for-real", 404, 404);

        assertThat(fetcher.probe(baseUrl + "/head-only", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.ALIVE, "ok", 200));
        assertThat(fetcher.probe(baseUrl + "/gone-for-real", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "404", 404));
        assertThat(requests).containsExactly("HEAD /head-only", "GET /head-only", "HEAD /gone-for-real", "GET /gone-for-real");
    }

    @Test
    void goneIsDead() {
        answer("/gone", 410, 410);

        assertThat(fetcher.probe(baseUrl + "/gone", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "410", 410));
        assertThat(requests).containsExactly("HEAD /gone", "GET /gone");
    }

    @Test
    void headGoneThatAGetServesIsAlive() {
        answer("/odd", 410, 206);

        assertThat(fetcher.probe(baseUrl + "/odd", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.ALIVE, "ok", 206));
    }

    @ParameterizedTest
    @ValueSource(ints = {405, 501})
    void methodNotAllowedFallsBackToGet(final int headStatus) {
        answer("/no-head", headStatus, 200);

        assertThat(fetcher.probe(baseUrl + "/no-head", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.ALIVE, "ok", 200));
        assertThat(requests).containsExactly("HEAD /no-head", "GET /no-head");
        assertThat(rangeHeaders).containsEntry("GET /no-head", "bytes=0-1023").doesNotContainKey("HEAD /no-head");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void forbiddenIsUnknown(final int status) {
        answer("/wall", status, status);

        assertThat(fetcher.probe(baseUrl + "/wall", BUDGET))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "http-" + status, status));
        assertThat(requests).containsExactly("HEAD /wall");
    }

    @Test
    void serverErrorIsUnknown() {
        answer("/down", 503, 503);

        assertThat(fetcher.probe(baseUrl + "/down", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "http-503", 503));
    }

    @Test
    void tooManyIsUnknown() {
        answer("/busy", 429, 429);

        assertThat(fetcher.probe(baseUrl + "/busy", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "http-429", 429));
    }

    @Test
    void headThatFailsAndAGetThatAnswersOtherwiseCountsTheGet() {
        answer("/teapot", 405, 503);

        assertThat(fetcher.probe(baseUrl + "/teapot", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "http-503", 503));
    }

    @Test
    void redirectToLiveIsAlive() {
        redirect("/old", "/new");
        answer("/new", 200, 200);

        assertThat(fetcher.probe(baseUrl + "/old", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.ALIVE, "ok", 200));
        assertThat(requests).containsExactly("HEAD /old", "HEAD /new");
    }

    @Test
    void redirectToMissingIsDead() {
        redirect("/moved", baseUrl + "/nowhere");
        answer("/nowhere", 404, 404);

        assertThat(fetcher.probe(baseUrl + "/moved", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "404", 404));
    }

    @Test
    void fiveHopsAreFollowed() {
        for (int hop = 1; hop <= 5; hop++) {
            redirect("/r" + hop, "/r" + (hop + 1));
        }
        answer("/r6", 200, 200);

        assertThat(fetcher.probe(baseUrl + "/r1", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.ALIVE, "ok", 200));
    }

    @Test
    void sixHopsIsUnknown() {
        for (int hop = 1; hop <= 6; hop++) {
            redirect("/r" + hop, "/r" + (hop + 1));
        }
        answer("/r7", 200, 200);

        assertThat(fetcher.probe(baseUrl + "/r1", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "redirects", 302));
        assertThat(requests).doesNotContain("HEAD /r7");
    }

    @Test
    void redirectToPrivateIsUnknown() {
        final Resolver resolver = host -> "internal.test".equals(host)
                ? new InetAddress[] {InetAddress.getByAddress(host, PRIVATE_ADDRESS)}
                : publicResolver.resolve(host);
        final var guarded = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), resolver);
        redirect("/out", "http://internal.test:" + server.getAddress().getPort() + "/admin");

        assertThat(guarded.probe(baseUrl + "/out", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "private", null));
        assertThat(requests).containsExactly("HEAD /out");
    }

    @Test
    void privateDestinationIsUnknownWithoutARequest() {
        answer("/ok", 200, 200);
        final var guarded = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(),
                host -> new InetAddress[] {InetAddress.getByAddress(host, PRIVATE_ADDRESS)});

        assertThat(guarded.probe(baseUrl + "/ok", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "private", null));
        assertThat(requests).isEmpty();
    }

    @Test
    void theRealResolverTreatsLoopbackAsPrivate() {
        answer("/ok", 200, 200);

        final var result = new PublicHttpFetcherConfiguration().destinationProber().probe(baseUrl + "/ok", BUDGET, host -> {});

        assertThat(result).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "private", null));
        assertThat(requests).isEmpty();
    }

    @Test
    void unknownHostIsNxdomain() {
        final var unresolvable = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), host -> {
            throw new UnknownHostException(host);
        });

        assertThat(unresolvable.probe("https://no-such-host.example/page", BUDGET))
                .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "nxdomain", null));
        assertThat(new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly()).probe("https://nothing.invalid/", BUDGET))
                .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "nxdomain", null));
    }

    @Test
    void aRedirectToAHostThatDoesNotResolveIsNxdomain() {
        final Resolver resolver = host -> {
            if ("expired-domain.test".equals(host)) {
                throw new UnknownHostException(host);
            }
            return publicResolver.resolve(host);
        };
        redirect("/parked", "https://expired-domain.test/landing");

        assertThat(new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), resolver).probe(baseUrl + "/parked", BUDGET))
                .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "nxdomain", null));
    }

    @Test
    void closedPortIsRefused() throws IOException {
        assertThat(fetcher.probe("http://127.0.0.1:" + closedPort() + "/", BUDGET))
                .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "refused", null));
    }

    @Test
    void plainHttpBehindHttpsIsTls() throws IOException {
        try (final var plain = PlainHttpSocket.start()) {
            assertThat(fetcher.probe(plain.httpsUrl() + "/plain", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "tls", null));
        }
    }

    @Test
    void untrustedCertificateIsTls() throws Exception {
        final var https = SelfSignedHttpsServer.start(keys, exchange -> send(exchange, 200));
        try {
            assertThat(fetcher.probe(https.url() + "/secure", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "tls", null));
        } finally {
            https.stop();
        }
    }

    @Test
    void silentServerIsTimeout() {
        server.createContext("/silent", exchange -> sleep(Duration.ofSeconds(5)));

        final var started = System.nanoTime();
        final var result = fetcher.probe(baseUrl + "/silent", Duration.ofSeconds(1));

        assertThat(result).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "timeout", null));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void oneBudgetCoversEveryHop() {
        server.createContext("/slow1", exchange -> {
            sleep(Duration.ofMillis(700));
            redirectNow(exchange, "/slow2");
        });
        server.createContext("/slow2", exchange -> {
            sleep(Duration.ofMillis(700));
            send(exchange, 200);
        });

        assertThat(fetcher.probe(baseUrl + "/slow1", Duration.ofSeconds(1)))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "timeout", null));
    }

    @Test
    void userAgentNamesTheChecker() {
        answer("/ua", 405, 200);

        fetcher.probe(baseUrl + "/ua", BUDGET);

        assertThat(PublicHttpFetcher.LINK_CHECKER_USER_AGENT)
                .isEqualTo("hrva.cc-linkcheck/1.0 (+https://app.hrva.cc/help/notifications#link-checker)");
        assertThat(userAgents).containsEntry("HEAD /ua", PublicHttpFetcher.LINK_CHECKER_USER_AGENT)
                .containsEntry("GET /ua", PublicHttpFetcher.LINK_CHECKER_USER_AGENT);
    }

    @Test
    void bodyIsNotReadPastTheLimit() {
        final var bigBody = new byte[64 * 1024 * 1024];
        final var written = new AtomicLong();
        server.createContext("/big", exchange -> {
            record(exchange);
            if ("HEAD".equals(exchange.getRequestMethod())) {
                send(exchange, 405);
                return;
            }
            exchange.sendResponseHeaders(200, bigBody.length);
            try (final var output = exchange.getResponseBody()) {
                for (int offset = 0; offset < bigBody.length; offset += 8192) {
                    output.write(bigBody, offset, 8192);
                    written.addAndGet(8192);
                }
            } catch (final IOException exception) {
                return;
            }
        });

        final var started = System.nanoTime();
        assertThat(fetcher.probe(baseUrl + "/big", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.ALIVE, "ok", 200));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        sleep(Duration.ofMillis(300));
        assertThat(written.get()).isLessThan(bigBody.length);
    }

    @Test
    void everyRequestWaitsForItsHostsTurnAndTheWaitIsNotCountedAgainstTheBudget() throws IOException {
        final Resolver resolver = host -> new InetAddress[] {InetAddress.getByAddress(host, PUBLIC_ADDRESS)};
        final var paced = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), resolver);
        final var turns = new CopyOnWriteArrayList<String>();
        final var everyAddress = HttpServer.create(new InetSocketAddress(0), 0);
        everyAddress.createContext("/landing", exchange -> {
            record(exchange);
            send(exchange, "HEAD".equals(exchange.getRequestMethod()) ? 404 : 200);
        });
        everyAddress.setExecutor(executor);
        everyAddress.start();
        try {
            redirect("/hop", "http://localhost:" + everyAddress.getAddress().getPort() + "/landing");

            final var result = paced.probe(baseUrl + "/hop", Duration.ofSeconds(1), host -> {
                turns.add(host);
                sleep(Duration.ofMillis(400));
            });

            assertThat(result).isEqualTo(new ProbeResult(ProbeOutcome.ALIVE, "ok", 200));
            assertThat(turns).containsExactly("127.0.0.1", "localhost", "localhost");
            assertThat(requests).containsExactly("HEAD /hop", "HEAD /landing", "GET /landing");
        } finally {
            everyAddress.stop(0);
        }
    }

    @Test
    void theAddressIsCheckedAgainAfterTheWaitRightBeforeTheRequest() {
        answer("/ok", 200, 200);
        final var rebound = new AtomicBoolean();
        final Resolver resolver = host -> new InetAddress[] {InetAddress.getByAddress(host, rebound.get() ? PRIVATE_ADDRESS : PUBLIC_ADDRESS)};
        final var guarded = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), resolver);

        final var result = guarded.probe(baseUrl + "/ok", BUDGET, host -> rebound.set(true));

        assertThat(result).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "private", null));
        assertThat(requests).isEmpty();
    }

    @Test
    void theGetFallbackChecksTheAddressAgainToo() {
        answer("/head-only", 404, 200);
        final var turns = new AtomicInteger();
        final Resolver resolver = host -> new InetAddress[] {InetAddress.getByAddress(host, turns.get() > 1 ? PRIVATE_ADDRESS : PUBLIC_ADDRESS)};
        final var guarded = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), resolver);

        final var result = guarded.probe(baseUrl + "/head-only", BUDGET, host -> turns.incrementAndGet());

        assertThat(result).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "private", null));
        assertThat(requests).containsExactly("HEAD /head-only");
    }

    @Test
    void aSlowResolverIsBoundByTheProbeBudget() {
        final var slow = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), host -> {
            sleep(Duration.ofSeconds(5));
            return publicResolver.resolve(host);
        });

        final var started = System.nanoTime();
        final var result = slow.probe("https://slow-dns.example/page", Duration.ofMillis(500));

        assertThat(result).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "timeout", null));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aRefusedConnectionIsDead() {
        assertThat(PublicHttpFetcher.classify(connectFailure(new ConnectException("Connection refused"))))
                .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "refused", null));
        assertThat(PublicHttpFetcher.classify(new ConnectException("Connection refused")))
                .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "refused", null));
        assertThat(PublicHttpFetcher.classify(connectFailure(connectFailure(new ClosedChannelException()))))
                .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "refused", null));
    }

    @Test
    void ourOwnCloseDuringAConnectIsNotARefusal() {
        assertThat(PublicHttpFetcher.classify(connectFailure(connectFailure(new AsynchronousCloseException()))))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "error", null));
        assertThat(PublicHttpFetcher.classify(connectFailure(connectFailure(new ClosedByInterruptException()))))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "error", null));
    }

    @Test
    void aClosedChannelOutsideAConnectIsNotARefusal() {
        assertThat(PublicHttpFetcher.classify(new IOException(new ClosedChannelException())))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "error", null));
    }

    @Test
    void anUnreachableNetworkIsUnknown() {
        assertThat(PublicHttpFetcher.classify(connectFailure(new SocketException("Network is unreachable"))))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "unreachable", null));
    }

    @Test
    void anUnreachableHostIsUnknown() {
        assertThat(PublicHttpFetcher.classify(connectFailure(new NoRouteToHostException("No route to host"))))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "unreachable", null));
    }

    @Test
    void anUnresolvedAddressIsUnknown() {
        assertThat(PublicHttpFetcher.classify(connectFailure(new UnresolvedAddressException())))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "unreachable", null));
    }

    @Test
    void aHandshakeThePeerAbortedIsUnknown() {
        assertThat(PublicHttpFetcher.classify(new IOException(new SSLHandshakeException("Remote host terminated the handshake", new EOFException()))))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "unreachable", null));
        assertThat(PublicHttpFetcher.classify(new SSLHandshakeException("handshake failed", new SocketException("Connection reset"))))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "unreachable", null));
        assertThat(PublicHttpFetcher.classify(new EOFException("EOF reached while reading")))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "unreachable", null));
    }

    @Test
    void aBrokenCertificateOrProtocolIsTls() {
        assertThat(PublicHttpFetcher.classify(new SSLHandshakeException("PKIX path building failed", new CertificateException("untrusted"))))
                .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "tls", null));
        assertThat(PublicHttpFetcher.classify(new SSLException("Unsupported or unrecognized SSL message")))
                .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "tls", null));
    }

    @Test
    void aTimeoutAnywhereInTheChainIsUnknown() {
        assertThat(PublicHttpFetcher.classify(new HttpConnectTimeoutException("connect timed out")))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "timeout", null));
        assertThat(PublicHttpFetcher.classify(new IOException(new HttpTimeoutException("request timed out"))))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "timeout", null));
    }

    @Test
    void anythingElseIsUnknownError() {
        assertThat(PublicHttpFetcher.classify(new IOException("something odd")))
                .isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "error", null));
    }

    @Test
    void anAddressThatIsNotAWebAddressIsUnknown() {
        assertThat(fetcher.probe("ftp://example.com/file", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "error", null));
        assertThat(fetcher.probe("not a url", BUDGET)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "error", null));
    }

    @Test
    void aUnicodeHostIsResolvedInItsAsciiForm() {
        final var resolved = new CopyOnWriteArrayList<String>();
        final var recording = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), host -> {
            resolved.add(host);
            throw new UnknownHostException(host);
        });

        assertThat(recording.probe("https://\u017euti.example/stranica", BUDGET))
                .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "nxdomain", null));
        assertThat(resolved).containsExactly("xn--uti-b3a.example");
    }

    private static ConnectException connectFailure(final Throwable rootCause) {
        final var failure = new ConnectException();
        failure.initCause(rootCause);
        return failure;
    }

    private void answer(final String path, final int headStatus, final int getStatus) {
        server.createContext(path, exchange -> {
            if (!exchange.getRequestURI().getPath().equals(path)) {
                send(exchange, 500);
                return;
            }
            record(exchange);
            send(exchange, "HEAD".equals(exchange.getRequestMethod()) ? headStatus : getStatus);
        });
    }

    private void redirect(final String path, final String location) {
        server.createContext(path, exchange -> {
            record(exchange);
            redirectNow(exchange, location);
        });
    }

    private void record(final HttpExchange exchange) {
        final var key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        requests.add(key);
        final var range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null) {
            rangeHeaders.put(key, range);
        }
        userAgents.put(key, exchange.getRequestHeaders().getFirst("User-Agent"));
    }

    private static void redirectNow(final HttpExchange exchange, final String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        send(exchange, 302);
    }

    private static void send(final HttpExchange exchange, final int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static int closedPort() throws IOException {
        try (final var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void sleep(final Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

}
