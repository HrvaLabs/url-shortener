package cc.hrva.urlshortener.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DiscordWebhookClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String WEBHOOK_PATH = "/api/webhooks/1234567890/s3cr3t-w3bh00k-t0k3n";
    private static final DiscordMessage MESSAGE = DiscordMessage.withoutMentionsOrEmbeds("New user: account #31 signed up with email");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final DiscordWebhookClient client = new DiscordWebhookClient(jsonMapper);
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private ExecutorService executor;
    private HttpServer server;
    private String webhookUrl;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.start();
        webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + WEBHOOK_PATH;
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void theMessageIsPostedAsJsonWithMentionsOffAndEmbedsSuppressed() {
        answer(exchange -> respond(exchange, 204, ""));

        assertThat(client.send(webhookUrl, MESSAGE, TIMEOUT)).isTrue();

        assertThat(received).hasSize(1);
        assertThat(received.getFirst().method()).isEqualTo("POST");
        assertThat(received.getFirst().contentType()).isEqualTo("application/json");
        final var body = jsonMapper.readTree(received.getFirst().body());
        assertThat(body.path("content").asString()).isEqualTo("New user: account #31 signed up with email");
        assertThat(body.path("allowed_mentions").path("parse").isArray()).isTrue();
        assertThat(body.path("allowed_mentions").path("parse").isEmpty()).isTrue();
        assertThat(body.path("flags").asInt()).isEqualTo(4);
    }

    @Test
    void aRefusalIsLoggedWithItsStatusButNeverTheWebhookUrl(final CapturedOutput output) {
        answer(exchange -> respond(exchange, 404, "{\"message\": \"Unknown Webhook\", \"code\": 10015}"));

        assertThat(client.send(webhookUrl, MESSAGE, TIMEOUT)).isFalse();

        assertThat(received).hasSize(1);
        assertThat(output).contains("Discord webhook refused the alerts, dropping them status=404")
                .doesNotContain("s3cr3t-w3bh00k-t0k3n")
                .doesNotContain("1234567890");
    }

    @Test
    void aServerErrorIsNotRetried() {
        answer(exchange -> respond(exchange, 502, ""));

        assertThat(client.send(webhookUrl, MESSAGE, TIMEOUT)).isFalse();

        assertThat(received).hasSize(1);
    }

    @Test
    void aShortRateLimitIsWaitedOutAndRetriedOnce() {
        final var calls = new AtomicInteger();
        answer(exchange -> {
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 429, "{\"message\": \"You are being rate limited.\", \"retry_after\": 0.05, \"global\": false}");
            } else {
                respond(exchange, 204, "");
            }
        });

        assertThat(client.send(webhookUrl, MESSAGE, TIMEOUT)).isTrue();

        assertThat(received).hasSize(2);
        assertThat(received.get(1).body()).isEqualTo(received.getFirst().body());
    }

    @Test
    void aSecondRateLimitDropsTheMessage() {
        answer(exchange -> respond(exchange, 429, "{\"retry_after\": 0.01}"));

        assertThat(client.send(webhookUrl, MESSAGE, TIMEOUT)).isFalse();

        assertThat(received).hasSize(2);
    }

    @Test
    void aRateLimitOfTenSecondsOrMoreIsNotWaitedFor(final CapturedOutput output) {
        answer(exchange -> respond(exchange, 429, "{\"retry_after\": 10.0}"));

        assertThat(client.send(webhookUrl, MESSAGE, TIMEOUT)).isFalse();

        assertThat(received).hasSize(1);
        assertThat(output).contains("status=429");
    }

    @Test
    void sendingWithoutWaitingNeverWaitsOutARateLimit() {
        answer(exchange -> respond(exchange, 429, "{\"retry_after\": 0.01}"));

        assertThat(client.sendWithoutWaiting(webhookUrl, MESSAGE, TIMEOUT)).isFalse();

        assertThat(received).hasSize(1);
    }

    @Test
    void sendingWithoutWaitingDeliversLikeSend() {
        answer(exchange -> respond(exchange, 204, ""));

        assertThat(client.sendWithoutWaiting(webhookUrl, MESSAGE, TIMEOUT)).isTrue();

        assertThat(received).hasSize(1);
    }

    @Test
    void aRateLimitHeaderIsUsedWhenTheBodyHasNoRetryAfter() {
        final var calls = new AtomicInteger();
        answer(exchange -> {
            if (calls.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200, "");
            }
        });

        assertThat(client.send(webhookUrl, MESSAGE, TIMEOUT)).isTrue();

        assertThat(received).hasSize(2);
    }

    @Test
    void aWebhookThatDoesNotAnswerInTimeIsDroppedAndLoggedByExceptionClassOnly(final CapturedOutput output) {
        answer(exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 204, "");
        });

        final var started = System.nanoTime();

        assertThat(client.send(webhookUrl, MESSAGE, Duration.ofMillis(200))).isFalse();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_500));
        assertThat(output).containsPattern("Discord webhook call failed, dropping the alerts exception=java\\.(net\\.http\\.Http|util\\.concurrent\\.)TimeoutException")
                .doesNotContain("s3cr3t-w3bh00k-t0k3n");
    }

    @Test
    void anUnreachableWebhookIsDroppedWithoutTheUrlInTheLog(final CapturedOutput output) {
        server.stop(0);

        assertThat(client.send(webhookUrl, MESSAGE, TIMEOUT)).isFalse();

        assertThat(output).contains("Discord webhook call failed, dropping the alerts exception=")
                .doesNotContain("s3cr3t-w3bh00k-t0k3n")
                .doesNotContain("127.0.0.1");
    }

    @Test
    void aMalformedWebhookUrlIsDroppedWithoutEchoingIt(final CapturedOutput output) {
        assertThat(client.send("https://discord.com/api/webhooks/1/not a url", MESSAGE, TIMEOUT)).isFalse();

        assertThat(output).contains("exception=java.lang.IllegalArgumentException")
                .doesNotContain("not a url");
    }

    private void answer(final Handler handler) {
        server.createContext(WEBHOOK_PATH, exchange -> {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            handler.handle(exchange);
        });
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Received(String method, String contentType, String body) {}

}
