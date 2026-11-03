package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
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
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserRunClientTest {

    private static final String TOKEN = "br-test-token-S3CRET-71c2";
    private static final String ACCOUNT = "account-1";
    private static final String SNAPSHOT = "/client/v4/accounts/account-1/browser-run/snapshot";
    private static final String TARGET = "https://findfavoritedate.life/?u=f6yp608";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties appProperties = new AppProperties();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(BrowserRunClient.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    private ExecutorService executor;
    private HttpServer server;
    private BrowserRunClient client;
    private Level previousLevel;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.start();
        appProperties.getCloudflare().setApiBase("http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4");
        appProperties.getCloudflare().setAccountId(ACCOUNT);
        appProperties.getCloudflare().setBrowserApiToken(TOKEN);
        client = new BrowserRunClient(appProperties, jsonMapper, Duration.ofSeconds(2));
        previousLevel = clientLogger.getLevel();
        clientLogger.setLevel(Level.DEBUG);
        logEvents.start();
        clientLogger.addAppender(logEvents);
    }

    @AfterEach
    void stopServer() {
        clientLogger.detachAppender(logEvents);
        clientLogger.setLevel(previousLevel);
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void sendsTheSnapshotRequest() {
        answer(200, taken(JPEG, "\"meta\":{\"status\":200,\"title\":\"Find your date\"}", "<html></html>"));

        client.snapshot(TARGET);

        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(SNAPSHOT);
            assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(request.contentType()).isEqualTo("application/json");
            assertThat(request.userAgent()).isEqualTo("hrva.cc-screenshots/1.0 (+https://hrva.cc/.well-known/security.txt)");
            assertThat(jsonMapper.readTree(request.body())).isEqualTo(jsonMapper.readTree("""
                    {"url": "https://findfavoritedate.life/?u=f6yp608",
                     "viewport": {"width": 390, "height": 844, "deviceScaleFactor": 2},
                     "userAgent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1",
                     "screenshotOptions": {"type": "jpeg", "quality": 70},
                     "gotoOptions": {"waitUntil": "networkidle2", "timeout": 20000}}
                    """));
        });
    }

    @Test
    void takenReturnsImageTitleAndStatus() {
        answer(200, taken(JPEG, "\"meta\":{\"status\":403,\"title\":\"  Sign in   to your bank \"}", "<html><title>Other</title></html>"));

        final var snapshot = client.snapshot(TARGET);

        assertThat(snapshot.outcome()).isEqualTo(PageSnapshot.Outcome.TAKEN);
        assertThat(snapshot.image()).isEqualTo(JPEG);
        assertThat(snapshot.contentType()).isEqualTo("image/jpeg");
        assertThat(snapshot.title()).isEqualTo("Sign in to your bank");
        assertThat(snapshot.status()).isEqualTo(403);
        assertThat(snapshot.failure()).isNull();
    }

    @Test
    void aPngAnswerIsStoredAsPng() {
        answer(200, taken(PNG, "\"meta\":{\"status\":200,\"title\":\"x\"}", ""));

        final var snapshot = client.snapshot(TARGET);

        assertThat(snapshot.outcome()).isEqualTo(PageSnapshot.Outcome.TAKEN);
        assertThat(snapshot.contentType()).isEqualTo("image/png");
    }

    @Test
    void titleFallsBackToTheHtml() {
        answer(200, taken(JPEG, "\"meta\":{\"status\":200}", "<html><head><title>Njuškalo prijava</title></head><body></body></html>"));

        final var snapshot = client.snapshot(TARGET);

        assertThat(snapshot.title()).isEqualTo("Njuškalo prijava");
        assertThat(snapshot.status()).isEqualTo(200);
    }

    @Test
    void aPageWithoutAnyTitleHasNone() {
        answer(200, taken(JPEG, "\"meta\":{}", "<html><body>hi</body></html>"));

        final var snapshot = client.snapshot(TARGET);

        assertThat(snapshot.outcome()).isEqualTo(PageSnapshot.Outcome.TAKEN);
        assertThat(snapshot.title()).isNull();
        assertThat(snapshot.status()).isNull();
    }

    @Test
    void dailyLimit429() {
        answer(429, "{\"success\":false,\"errors\":[{\"code\":429,\"message\":\"Error processing the request: Unable to create new browser: code: 429: "
                + "message: Browser time limit exceeded for today\"}]}");

        final var snapshot = client.snapshot(TARGET);

        assertThat(snapshot.outcome()).isEqualTo(PageSnapshot.Outcome.DAILY_LIMIT);
        assertThat(snapshot.image()).isNull();
    }

    @Test
    void rateLimit429ReadsRetryAfter() {
        server.createContext(SNAPSHOT, exchange -> {
            record(exchange);
            exchange.getResponseHeaders().add("Retry-After", "37");
            send(exchange, 429, "{\"success\":false,\"errors\":[{\"code\":2001,\"message\":\"Rate limit exceeded\"}]}");
        });

        final var snapshot = client.snapshot(TARGET);

        assertThat(snapshot.outcome()).isEqualTo(PageSnapshot.Outcome.RATE_LIMITED);
        assertThat(snapshot.retryAfter()).isEqualTo(Duration.ofSeconds(37));
    }

    @Test
    void rateLimit429WithoutRetryAfterWaitsTenSeconds() {
        answer(429, "{\"success\":false,\"errors\":[{\"code\":2001,\"message\":\"Rate limit exceeded\"}]}");

        final var snapshot = client.snapshot(TARGET);

        assertThat(snapshot.outcome()).isEqualTo(PageSnapshot.Outcome.RATE_LIMITED);
        assertThat(snapshot.retryAfter()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void timeoutFailsWithAReason() {
        server.createContext(SNAPSHOT, exchange -> sleep(Duration.ofSeconds(5)));

        final var started = System.nanoTime();
        final var snapshot = client.snapshot(TARGET);

        assertThat(snapshot).isEqualTo(PageSnapshot.failed("The page didn't load within 20 seconds."));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void aNavigationTimeoutFromCloudflareFailsWithTheSameReason() {
        answer(422, "{\"success\":false,\"errors\":[{\"code\":5011,\"message\":\"Navigation timeout of 20000 ms exceeded\"}]}");

        assertThat(client.snapshot(TARGET)).isEqualTo(PageSnapshot.failed("The page didn't load within 20 seconds."));
    }

    @Test
    void aGatewayTimeoutIsNotAPageTimeout() {
        answer(504, "<html><head><title>504 Gateway Timeout</title></head></html>");

        assertThat(client.snapshot(TARGET)).isEqualTo(PageSnapshot.failed("Cloudflare couldn't open the page (504)."));
    }

    @Test
    void aLinkThatSaysTimeoutIsNotAPageTimeout() {
        answer(422, "{\"success\":false,\"errors\":[{\"code\":5010,\"message\":\"Could not open https://timeout.example/session-timeout\"}]}");

        assertThat(client.snapshot(TARGET)).isEqualTo(PageSnapshot.failed("Cloudflare couldn't open the page (422)."));
    }

    @Test
    void anInterruptedCallIsItsOwnOutcomeAndKeepsTheInterrupt() {
        answer(200, taken(JPEG, "\"meta\":{\"status\":200}", "<html></html>"));

        Thread.currentThread().interrupt();
        final var snapshot = client.snapshot(TARGET);
        final var stillInterrupted = Thread.interrupted();

        assertThat(snapshot).isEqualTo(PageSnapshot.interrupted());
        assertThat(stillInterrupted).isTrue();
    }

    @Test
    void aRefusedTokenIsItsOwnOutcome() {
        answer(401, "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error\"}]}");
        assertThat(client.snapshot(TARGET).outcome()).isEqualTo(PageSnapshot.Outcome.TOKEN_REFUSED);

        server.removeContext(SNAPSHOT);
        answer(403, "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error\"}]}");
        assertThat(client.snapshot(TARGET).outcome()).isEqualTo(PageSnapshot.Outcome.TOKEN_REFUSED);
    }

    @Test
    void oversizedImageFails() {
        answer(200, taken(new byte[3 * 1024 * 1024 + 1], "\"meta\":{\"status\":200}", ""));

        assertThat(client.snapshot(TARGET)).isEqualTo(PageSnapshot.failed("The screenshot was too large."));
    }

    @Test
    void otherErrorsFailWithTheStatus() throws IOException {
        answer(500, "{\"success\":false,\"errors\":[{\"code\":1000,\"message\":\"Internal\"}]}");
        assertThat(client.snapshot(TARGET)).isEqualTo(PageSnapshot.failed("Cloudflare couldn't open the page (500)."));

        server.removeContext(SNAPSHOT);
        answer(200, "{\"success\":false,\"errors\":[{\"code\":1,\"message\":\"no\"}]}");
        assertThat(client.snapshot(TARGET)).isEqualTo(PageSnapshot.failed("Cloudflare couldn't open the page (200)."));

        server.removeContext(SNAPSHOT);
        answer(200, "{\"success\":true,\"result\":{\"screenshot\":\"" + Base64.getEncoder().encodeToString("not an image".getBytes(StandardCharsets.UTF_8))
                + "\"}}");
        assertThat(client.snapshot(TARGET)).isEqualTo(PageSnapshot.failed("Cloudflare couldn't open the page (200)."));

        server.removeContext(SNAPSHOT);
        answer(200, "not json");
        assertThat(client.snapshot(TARGET)).isEqualTo(PageSnapshot.failed("Cloudflare couldn't open the page (200)."));

        appProperties.getCloudflare().setApiBase("http://127.0.0.1:" + closedPort() + "/client/v4");
        assertThat(client.snapshot(TARGET)).isEqualTo(PageSnapshot.failed("Cloudflare couldn't open the page (no answer)."));
    }

    @Test
    void withoutCredentialsNoCallIsMade() {
        answer(200, taken(JPEG, "\"meta\":{}", ""));

        appProperties.getCloudflare().setBrowserApiToken(" ");
        assertThat(client.snapshot(TARGET).outcome()).isEqualTo(PageSnapshot.Outcome.NOT_CONFIGURED);

        appProperties.getCloudflare().setBrowserApiToken(TOKEN);
        appProperties.getCloudflare().setAccountId(null);
        assertThat(client.snapshot(TARGET).outcome()).isEqualTo(PageSnapshot.Outcome.NOT_CONFIGURED);

        assertThat(received).isEmpty();
    }

    @Test
    void theTokenAndTheLinkAreNeverLogged() {
        answer(500, "{\"errors\":[{\"message\":\"" + TARGET + "\"}]}");

        client.snapshot(TARGET);

        assertThat(logEvents.list).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(TOKEN).doesNotContain("Bearer").doesNotContain("findfavoritedate"));
    }

    private String taken(final byte[] image, final String meta, final String content) {
        final var result = jsonMapper.createObjectNode();
        result.put("success", true);
        result.putObject("result").put("screenshot", Base64.getEncoder().encodeToString(image)).put("content", content);
        final var body = jsonMapper.writeValueAsString(result);
        return body.substring(0, body.length() - 1) + "," + meta + "}";
    }

    private void answer(final int status, final String body) {
        server.createContext(SNAPSHOT, exchange -> {
            record(exchange);
            send(exchange, status, body);
        });
    }

    private void record(final HttpExchange exchange) throws IOException {
        final var headers = exchange.getRequestHeaders();
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers.getFirst("Authorization"),
                headers.getFirst("Content-Type"), headers.getFirst("User-Agent"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private static void send(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (final var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
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

    private record Received(String method, String path, String authorization, String contentType, String userAgent, String body) {
    }

}
