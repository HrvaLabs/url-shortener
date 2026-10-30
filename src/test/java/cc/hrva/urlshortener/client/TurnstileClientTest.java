package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TurnstileClientTest {

    private static final String SITEVERIFY = "/turnstile/v0/siteverify";
    private static final String SECRET = "2x0000000000000000000000000000000AA";
    private static final String TOKEN = "XXXX.DUMMY.TOKEN.XXXX";
    private static final Duration TEST_TIMEOUT = Duration.ofMillis(200);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties appProperties = new AppProperties();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(TurnstileClient.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<HttpHandler> siteverify = new AtomicReference<>();

    private ExecutorService executor;
    private HttpServer server;
    private TurnstileClient client;
    private Level previousLevel;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext(SITEVERIFY, exchange -> siteverify.get().handle(exchange));
        server.start();
        appProperties.getTurnstile().setSiteKey("1x00000000000000000000AA");
        appProperties.getTurnstile().setSecretKey(SECRET);
        client = clientFor(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + SITEVERIFY));
        previousLevel = clientLogger.getLevel();
        clientLogger.setLevel(Level.DEBUG);
        logEvents.start();
        clientLogger.addAppender(logEvents);
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        clientLogger.detachAppender(logEvents);
        clientLogger.setLevel(previousLevel);
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void theRequestCarriesSecretAndResponseOnly() {
        answer(200, "{\"success\":true,\"hostname\":\"app.hrva.cc\",\"error-codes\":[]}");

        client.verify(TOKEN);

        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(SITEVERIFY);
            assertThat(request.contentType()).isEqualTo("application/x-www-form-urlencoded");
            assertThat(request.form()).containsExactlyInAnyOrderEntriesOf(Map.of("secret", SECRET, "response", TOKEN));
        });
    }

    @Test
    void aTokenCannotAddFieldsToTheRequest() {
        answer(200, "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");
        final var crafted = "a+b/c=d&remoteip=198.51.100.1&secret=other";

        client.verify(crafted);

        assertThat(received).singleElement()
                .satisfies(request -> assertThat(request.form()).containsExactlyInAnyOrderEntriesOf(Map.of("secret", SECRET, "response", crafted)));
    }

    @Test
    void aSuccessCarriesTheHostname() {
        answer(200, "{\"success\":true,\"challenge_ts\":\"2026-10-30T10:00:00.000Z\",\"hostname\":\"app.hrva.cc\",\"error-codes\":[],"
                + "\"action\":\"\",\"cdata\":\"\"}");

        assertThat(client.verify(TOKEN)).isEqualTo(new SiteverifyResult.Answered(true, "app.hrva.cc", List.of()));
    }

    @Test
    void aRefusalCarriesTheErrorCodes() {
        answer(200, "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"],\"messages\":[]}");

        assertThat(client.verify(TOKEN)).isEqualTo(new SiteverifyResult.Answered(false, null, List.of("timeout-or-duplicate")));
    }

    @Test
    void aClosedPortIsUnreachable() throws IOException {
        final int closedPort;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        final var result = clientFor(URI.create("http://127.0.0.1:" + closedPort + SITEVERIFY)).verify(TOKEN);

        assertThat(result).isInstanceOf(SiteverifyResult.Unreachable.class);
    }

    @Test
    void aServerSlowerThanTheTimeoutIsUnreachable() {
        siteverify.set(exchange -> {
            record(exchange);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"success\":true,\"hostname\":\"app.hrva.cc\"}");
        });
        final var started = System.nanoTime();

        final var result = client.verify(TOKEN);

        assertThat(result).isEqualTo(new SiteverifyResult.Unreachable("timeout"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aServerErrorIsUnreachable() {
        answer(500, "{\"success\":false}");

        assertThat(client.verify(TOKEN)).isEqualTo(new SiteverifyResult.Unreachable("status 500"));
    }

    @Test
    void aRedirectIsNotFollowed() {
        siteverify.set(exchange -> {
            record(exchange);
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere");
            respond(exchange, 302, "");
        });
        server.createContext("/elsewhere", exchange -> {
            record(exchange);
            respond(exchange, 200, "{\"success\":true,\"hostname\":\"app.hrva.cc\"}");
        });

        assertThat(client.verify(TOKEN)).isEqualTo(new SiteverifyResult.Unreachable("status 302"));
        assertThat(received).singleElement().extracting(Received::path).isEqualTo(SITEVERIFY);
    }

    @Test
    void aBodyThatIsNotJsonIsUnreachable() {
        answer(200, "<html>maintenance</html>");

        assertThat(client.verify(TOKEN)).isEqualTo(new SiteverifyResult.Unreachable("unreadable"));
    }

    @Test
    void aBodyWithoutSuccessIsUnreachable() {
        answer(200, "{\"hostname\":\"app.hrva.cc\"}");

        assertThat(client.verify(TOKEN)).isEqualTo(new SiteverifyResult.Unreachable("unreadable"));
    }

    @Test
    void neitherTheTokenNorTheSecretIsLogged() {
        answer(500, "{\"success\":false}");
        client.verify(TOKEN);
        answer(200, "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");
        client.verify(TOKEN);

        assertThat(logEvents.list).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(TOKEN, SECRET));
    }

    private TurnstileClient clientFor(final URI siteverify) {
        return new TurnstileClient(appProperties, jsonMapper, siteverify, TEST_TIMEOUT, TEST_TIMEOUT);
    }

    private void answer(final int status, final String body) {
        siteverify.set(exchange -> {
            record(exchange);
            respond(exchange, status, body);
        });
    }

    private void record(final HttpExchange exchange) throws IOException {
        final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"), formOf(body)));
    }

    private static Map<String, String> formOf(final String body) {
        if (body.isEmpty()) {
            return Map.of();
        }
        return Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""));
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private record Received(String method, String path, String contentType, Map<String, String> form) {
    }

}
