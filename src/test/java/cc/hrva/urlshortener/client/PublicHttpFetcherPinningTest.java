package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.util.PublicAddresses;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicHttpFetcherPinningTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Consumer<String> NO_HOOK = host -> {};
    private static final Predicate<InetAddress> LOOPBACK_OR_PUBLIC =
            address -> InetAddress.getLoopbackAddress().equals(address) || PublicAddresses.isPublicAddress(address);

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> hostHeaders = new CopyOnWriteArrayList<>();

    @TempDir
    private Path keys;
    private ExecutorService executor;
    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            hostHeaders.add(exchange.getRequestHeaders().getFirst("Host"));
            if (exchange.getRequestURI().getPath().equals("/start")) {
                exchange.getResponseHeaders().add("Location", "http://internal.test:" + port + "/admin");
                send(exchange, 302, "");
                return;
            }
            send(exchange, 200, "ok");
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void connectsToTheAddressItCheckedWithoutAskingAgain() throws IOException {
        final var lookups = new AtomicInteger();
        final Resolver resolver = host -> new InetAddress[] {lookups.incrementAndGet() == 1 ? InetAddress.getLoopbackAddress() : address("10.0.0.1")};
        final var fetcher = new PublicHttpFetcher(url -> true, resolver, LOOPBACK_OR_PUBLIC);

        final var result = fetcher.fetch("http://shop.test:" + port + "/list", 100, null, null, TIMEOUT);

        try {
            assertThat(result.body()).content().isEqualTo("ok");
            assertThat(lookups).hasValue(1);
            assertThat(hostHeaders).containsExactly("shop.test:" + port);
        } finally {
            Files.deleteIfExists(result.body());
        }
    }

    @Test
    void aResolverThatHangsIsCutOffAtTheDeadlineOnEveryPath() {
        final Resolver hangs = host -> {
            try {
                Thread.sleep(30_000);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            throw new UnknownHostException(host);
        };
        final var deadline = Duration.ofMillis(300);
        final var fetcher = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), hangs, PublicAddresses::isPublicAddress);
        final var httpsFetcher = new PublicHttpFetcher(PublicHttpFetcher.httpsPublicOnly(), hangs, PublicAddresses::isPublicAddress);

        final var started = System.nanoTime();
        assertThatThrownBy(() -> fetcher.fetch("http://dead-nameserver.example/", 1024, Map.of(), deadline)).hasMessage("timed out");
        assertThatThrownBy(() -> fetcher.fetchFirst("http://dead-nameserver.example/", 1024, Map.of(), deadline)).hasMessage("timed out");
        assertThatThrownBy(() -> httpsFetcher.fetch("https://dead-nameserver.example/", 1024, Map.of(), deadline)).hasMessage("timed out");
        assertThatThrownBy(() -> fetcher.followRedirects("http://dead-nameserver.example/", 3, deadline)).hasMessage("timed out");
        assertThat(fetcher.probe("http://dead-nameserver.example/", deadline, NO_HOOK)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "timeout", null));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void aPrivateAddressLiteralIsStillRefusedBeforeAnyLookup() {
        final Resolver neverAsked = host -> {
            throw new AssertionError("no lookup for an address literal");
        };
        final var fetcher = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly(), neverAsked, PublicAddresses::isPublicAddress);

        assertThatThrownBy(() -> fetcher.fetch("http://169.254.169.254/latest/meta-data/", 1024, Map.of(), TIMEOUT)).isInstanceOf(UrlValidationException.class);
        assertThatThrownBy(() -> fetcher.fetch("http://[::ffff:10.0.0.1]/", 1024, Map.of(), TIMEOUT)).isInstanceOf(UrlValidationException.class);
        assertThatThrownBy(() -> fetcher.fetch("file:///etc/passwd", 1024, Map.of(), TIMEOUT)).isInstanceOf(UrlValidationException.class);
    }

    @Test
    void aNameThatRebindsToAPrivateAddressIsNeverRequested() throws IOException {
        final var lookups = new AtomicInteger();
        final Resolver rebinding = host -> new InetAddress[] {lookups.incrementAndGet() == 1 ? InetAddress.getLoopbackAddress() : address("10.0.0.1")};
        final var fetcher = new PublicHttpFetcher(url -> true, rebinding, LOOPBACK_OR_PUBLIC);

        Files.deleteIfExists(fetcher.fetch("http://shop.test:" + port + "/first", 100, null, null, TIMEOUT).body());

        assertThatThrownBy(() -> fetcher.fetch("http://shop.test:" + port + "/second", 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public address");
        assertThat(requests).containsExactly("GET /first");
    }

    @ParameterizedTest
    @ValueSource(strings = {"::ffff:10.0.0.1", "64:ff9b::a00:1", "169.254.169.254", "127.0.0.1 10.0.0.1", "127.0.0.1 fd00::1",
            "fd00::1 127.0.0.1"})
    void anAnswerWithAnyNonPublicAddressIsRefusedOnEveryPath(final String answer) {
        final Resolver resolver = host -> Arrays.stream(StringUtils.split(answer)).map(PublicHttpFetcherPinningTest::address).toArray(InetAddress[]::new);
        final var fetcher = new PublicHttpFetcher(url -> true, resolver, LOOPBACK_OR_PUBLIC);
        final var url = "http://shop.test:" + port + "/page";

        assertThatThrownBy(() -> fetcher.fetch(url, 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public address");
        assertThatThrownBy(() -> fetcher.followRedirects(url, 5, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public address");
        assertThat(fetcher.probe(url, TIMEOUT, NO_HOOK)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "private", null));
        assertThat(fetcher.post(url, "application/json", Map.of(), new byte[0], TIMEOUT)).isEqualTo(new PostResult(0, "not-allowed"));
        assertThat(requests).isEmpty();
    }

    @Test
    void aRedirectToANameThatResolvesPrivateIsNeverRequested() {
        final Resolver resolver = host -> new InetAddress[] {"internal.test".equals(host) ? address("10.0.0.5") : InetAddress.getLoopbackAddress()};
        final var fetcher = new PublicHttpFetcher(url -> true, resolver, LOOPBACK_OR_PUBLIC);
        final var start = "http://shop.test:" + port + "/start";

        assertThatThrownBy(() -> fetcher.fetch(start, 100, null, null, TIMEOUT))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("not a public address");
        assertThat(fetcher.followRedirects(start, 5, TIMEOUT)).extracting(RedirectHop::normalized)
                .containsExactly(start, "http://internal.test:" + port + "/admin");
        assertThat(fetcher.probe(start, TIMEOUT, NO_HOOK)).isEqualTo(new ProbeResult(ProbeOutcome.UNKNOWN, "private", null));
        assertThat(requests).containsExactly("GET /start", "GET /start", "HEAD /start");
    }

    @Test
    void tlsVerifiesTheNameRequestedNotThePinnedAddress() throws Exception {
        final var https = SelfSignedHttpsServer.startFor(keys, "pinned.test", exchange -> {
            hostHeaders.add(exchange.getRequestHeaders().getFirst("Host"));
            send(exchange, 200, "secure");
        });
        try {
            final Resolver resolver = host -> new InetAddress[] {InetAddress.getLoopbackAddress()};
            final var fetcher = new PublicHttpFetcher(url -> true, resolver, LOOPBACK_OR_PUBLIC, https.trustingContext());

            final var result = fetcher.fetch("https://pinned.test:" + https.port() + "/ok", 100, null, null, TIMEOUT);
            try {
                assertThat(result.body()).content().isEqualTo("secure");
                assertThat(hostHeaders).containsExactly("pinned.test:" + https.port());
            } finally {
                Files.deleteIfExists(result.body());
            }

            assertThatThrownBy(() -> fetcher.fetch("https://other.test:" + https.port() + "/ok", 100, null, null, TIMEOUT))
                    .isInstanceOf(UrlValidationException.class)
                    .hasMessage("connection failed");
            assertThat(fetcher.probe("https://other.test:" + https.port() + "/ok", TIMEOUT, NO_HOOK))
                    .isEqualTo(new ProbeResult(ProbeOutcome.DEAD, "tls", null));
            assertThat(hostHeaders).hasSize(1);
        } finally {
            https.stop();
        }
    }

    private static InetAddress address(final String literal) {
        try {
            if (literal.startsWith("::ffff:")) {
                final var mapped = new byte[16];
                mapped[10] = (byte) 0xFF;
                mapped[11] = (byte) 0xFF;
                System.arraycopy(InetAddress.ofLiteral(literal.substring(7)).getAddress(), 0, mapped, 12, 4);
                return Inet6Address.getByAddress(null, mapped, 0);
            }
            return InetAddress.ofLiteral(literal);
        } catch (final UnknownHostException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static void send(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (final var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

}
