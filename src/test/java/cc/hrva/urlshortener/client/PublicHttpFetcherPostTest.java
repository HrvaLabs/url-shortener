package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.PublicHttpFetcherConfiguration;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PublicHttpFetcherPostTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final byte[] BODY = "{\"event\":\"test\"}".getBytes(StandardCharsets.UTF_8);
    private static final String JSON = "application/json";

    private final List<ReceivedPost> received = new CopyOnWriteArrayList<>();
    private final PublicHttpFetcher permissiveFetcher = new PublicHttpFetcher(url -> true);
    private final Logger fetcherLogger = (Logger) LoggerFactory.getLogger(PublicHttpFetcher.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    @TempDir
    private Path keys;
    private ExecutorService executor;
    private HttpServer server;
    private String baseUrl;
    private Level previousLevel;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        previousLevel = fetcherLogger.getLevel();
        fetcherLogger.setLevel(Level.DEBUG);
        logEvents.start();
        fetcherLogger.addAppender(logEvents);
    }

    @AfterEach
    void stopServer() {
        fetcherLogger.detachAppender(logEvents);
        fetcherLogger.setLevel(previousLevel);
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void postsBodyAndHeaders() {
        answer("/hook", 204);

        final var result = permissiveFetcher.post(baseUrl + "/hook", JSON,
                Map.of("X-Hrva-Event", "test", "X-Hrva-Delivery", "test-1"), BODY, TIMEOUT);

        assertThat(result).isEqualTo(new PostResult(204, null));
        assertThat(result.delivered()).isTrue();
        assertThat(received).singleElement().satisfies(post -> {
            assertThat(post.method()).isEqualTo("POST");
            assertThat(post.contentType()).isEqualTo(JSON);
            assertThat(post.event()).isEqualTo("test");
            assertThat(post.delivery()).isEqualTo("test-1");
            assertThat(post.body()).containsExactly(BODY);
        });
    }

    @Test
    void returnsTheStatusAsData() {
        answer("/missing", 404);
        answer("/broken", 500);

        final var missing = permissiveFetcher.post(baseUrl + "/missing", JSON, Map.of(), BODY, TIMEOUT);
        final var broken = permissiveFetcher.post(baseUrl + "/broken", JSON, Map.of(), BODY, TIMEOUT);

        assertThat(missing).isEqualTo(new PostResult(404, null));
        assertThat(missing.delivered()).isFalse();
        assertThat(broken).isEqualTo(new PostResult(500, null));
    }

    @Test
    void neverFollowsRedirects() {
        answer("/final", 200);
        server.createContext("/moved", exchange -> {
            record(exchange);
            exchange.getResponseHeaders().add("Location", baseUrl + "/final");
            send(exchange, 302);
        });

        final var result = permissiveFetcher.post(baseUrl + "/moved", JSON, Map.of(), BODY, TIMEOUT);

        assertThat(result).isEqualTo(new PostResult(302, "redirected"));
        assertThat(result.delivered()).isFalse();
        assertThat(received).extracting(ReceivedPost::path).containsExactly("/moved");
    }

    @Test
    void httpIsRefusedByTheHttpsPolicy() {
        answer("/hook", 204);
        final var channelPoster = new PublicHttpFetcherConfiguration().channelPoster();

        assertThat(channelPoster.post(baseUrl + "/hook", JSON, Map.of(), BODY, TIMEOUT)).isEqualTo(new PostResult(0, "not-allowed"));
        assertThat(channelPoster.post("http://1.1.1.1/hook", JSON, Map.of(), BODY, TIMEOUT)).isEqualTo(new PostResult(0, "not-allowed"));
        assertThat(received).isEmpty();
    }

    @Test
    void privateAddressIsRefused() throws Exception {
        final var reached = new AtomicLong();
        final var https = SelfSignedHttpsServer.start(keys, exchange -> {
            reached.incrementAndGet();
            send(exchange, 204);
        });
        try {
            final var channelPoster = new PublicHttpFetcherConfiguration().channelPoster();

            assertThat(channelPoster.post(https.url() + "/hook", JSON, Map.of(), BODY, TIMEOUT)).isEqualTo(new PostResult(0, "not-allowed"));
            assertThat(channelPoster.post("https://10.0.0.5/hook", JSON, Map.of(), BODY, TIMEOUT)).isEqualTo(new PostResult(0, "not-allowed"));
            assertThat(channelPoster.post("not a url", JSON, Map.of(), BODY, TIMEOUT)).isEqualTo(new PostResult(0, "not-allowed"));
            assertThat(reached).hasValue(0);
        } finally {
            https.stop();
        }
    }

    @Test
    void timeoutIsData() {
        server.createContext("/silent", exchange -> sleep(Duration.ofSeconds(5)));

        final var started = System.nanoTime();
        final var result = permissiveFetcher.post(baseUrl + "/silent", JSON, Map.of(), BODY, Duration.ofMillis(500));

        assertThat(result).isEqualTo(new PostResult(0, "timeout"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void aConnectionThatFailsIsData() throws IOException {
        assertThat(permissiveFetcher.post("http://127.0.0.1:" + closedPort() + "/hook", JSON, Map.of(), BODY, TIMEOUT))
                .isEqualTo(new PostResult(0, "error"));
    }

    @Test
    void aHeaderValueThatCannotBeSentIsInvalidNotAnError() {
        assertThat(permissiveFetcher.post(baseUrl + "/hook", JSON, Map.of("Authorization", "Bearer tk one\ntwo"), BODY, TIMEOUT))
                .isEqualTo(new PostResult(0, "invalid"));
    }

    @Test
    void aLargeAnswerIsNotRead() {
        final var written = new AtomicLong();
        final var answer = new byte[64 * 1024 * 1024];
        server.createContext("/chatty", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, answer.length);
            try (final var output = exchange.getResponseBody()) {
                for (int offset = 0; offset < answer.length; offset += 8192) {
                    output.write(answer, offset, 8192);
                    written.addAndGet(8192);
                }
            } catch (final IOException exception) {
                return;
            }
        });

        final var started = System.nanoTime();
        assertThat(permissiveFetcher.post(baseUrl + "/chatty", JSON, Map.of(), BODY, TIMEOUT)).isEqualTo(new PostResult(200, null));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        sleep(Duration.ofMillis(300));
        assertThat(written.get()).isLessThan(answer.length);
    }

    @Test
    void nothingSecretIsLogged() throws Exception {
        final var path = "/bot123456:SECRETTOKEN/sendMessage";
        final var https = SelfSignedHttpsServer.start(keys, exchange -> send(exchange, 200));
        final var plainSocket = PlainHttpSocket.start();
        server.createContext("/silent", exchange -> sleep(Duration.ofSeconds(5)));
        try {
            final var untrusted = permissiveFetcher.post(https.url() + path, JSON, Map.of(), BODY, TIMEOUT);
            final var plain = permissiveFetcher.post(plainSocket.httpsUrl() + path, JSON, Map.of(), BODY, TIMEOUT);
            final var refused = permissiveFetcher.post("https://127.0.0.1:" + closedPort() + path, JSON, Map.of(), BODY, TIMEOUT);
            final var silent = permissiveFetcher.post(baseUrl + "/silent" + path, JSON, Map.of(), BODY, Duration.ofMillis(300));
            final var invalid = permissiveFetcher.post("https://127.0.0.1/bot123456:SECRETTOKEN/send Message", JSON, Map.of(), BODY, TIMEOUT);

            assertThat(List.of(untrusted, plain, refused)).containsOnly(new PostResult(0, "error"));
            assertThat(invalid).isEqualTo(new PostResult(0, "invalid"));
            assertThat(silent).isEqualTo(new PostResult(0, "timeout"));
        } finally {
            https.stop();
            plainSocket.close();
        }

        assertThat(logEvents.list).hasSize(5);
        assertThat(logEvents.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("SECRETTOKEN").doesNotContain("127.0.0.1");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private void answer(final String path, final int status) {
        server.createContext(path, exchange -> {
            record(exchange);
            send(exchange, status);
        });
    }

    private void record(final HttpExchange exchange) throws IOException {
        final var headers = exchange.getRequestHeaders();
        received.add(new ReceivedPost(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                headers.getFirst("Content-Type"), headers.getFirst("X-Hrva-Event"), headers.getFirst("X-Hrva-Delivery"),
                exchange.getRequestBody().readAllBytes()));
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

    private record ReceivedPost(String method, String path, String contentType, String event, String delivery, byte[] body) {
    }

}
