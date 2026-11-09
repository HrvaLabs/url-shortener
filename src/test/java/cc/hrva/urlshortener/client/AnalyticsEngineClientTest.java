package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.AnalyticsEngineException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsEngineClientTest {

    private static final String TOKEN = "ae-test-token-S3CRET-4f9d";
    private static final String ACCOUNT = "account-1";
    private static final String SQL_PATH = "/client/v4/accounts/account-1/analytics_engine/sql";
    private static final String SQL = "SELECT SUM(_sample_interval) AS visits FROM hrva_landings WHERE timestamp > NOW() - INTERVAL '30' DAY";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties appProperties = new AppProperties();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(AnalyticsEngineClient.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    private ExecutorService executor;
    private HttpServer server;
    private AnalyticsEngineClient client;
    private Level previousLevel;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.start();
        appProperties.getCloudflare().setApiBase("http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4");
        appProperties.getCloudflare().setAccountId(ACCOUNT);
        appProperties.getCloudflare().setAnalyticsApiToken(TOKEN);
        client = new AnalyticsEngineClient(appProperties, jsonMapper, Duration.ofSeconds(1));
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
    void sendsTheSqlWithTheBearerToken() {
        answer(200, "{\"meta\":[],\"data\":[],\"rows\":0}");

        client.query(SQL);

        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(SQL_PATH);
            assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(request.body()).isEqualTo(SQL + " FORMAT JSON");
        });
    }

    @Test
    void returnsTheDataRows() {
        answer(200, "{\"meta\":[],\"data\":[{\"source\":\"chatgpt.com\",\"kind\":\"utm\",\"visits\":13},{\"source\":\"direct\",\"kind\":\"direct\",\"visits\":\"7\"}],\"rows\":2}");

        final var rows = client.query(SQL);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("source", "chatgpt.com").containsEntry("kind", "utm");
        assertThat(((Number) rows.get(0).get("visits")).longValue()).isEqualTo(13L);
        assertThat(rows.get(1)).containsEntry("visits", "7");
    }

    @Test
    void aNon2xxAnswerThrows() {
        answer(403, "{\"success\":false,\"errors\":[{\"message\":\"Authentication error\"}]}");

        assertThatThrownBy(() -> client.query(SQL))
                .isInstanceOf(AnalyticsEngineException.class)
                .hasMessageNotContaining(TOKEN)
                .hasMessageContaining("403");
    }

    @Test
    void anAnswerWithoutDataThrows() {
        answer(200, "not json");

        assertThatThrownBy(() -> client.query(SQL)).isInstanceOf(AnalyticsEngineException.class);
    }

    @Test
    void aSlowAnswerTimesOut() {
        server.createContext(SQL_PATH, exchange -> sleep(Duration.ofSeconds(4)));

        final var started = System.nanoTime();

        assertThatThrownBy(() -> client.query(SQL)).isInstanceOf(AnalyticsEngineException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void configuredNeedsTheTokenAndTheAccount() {
        assertThat(client.configured()).isTrue();

        appProperties.getCloudflare().setAnalyticsApiToken(" ");
        assertThat(client.configured()).isFalse();

        appProperties.getCloudflare().setAnalyticsApiToken(TOKEN);
        appProperties.getCloudflare().setAccountId(null);
        assertThat(client.configured()).isFalse();
    }

    @Test
    void theTokenAndTheSqlNeverReachTheLog() {
        answer(500, "{\"errors\":[{\"message\":\"" + SQL + "\"}]}");

        assertThatThrownBy(() -> client.query(SQL)).isInstanceOf(AnalyticsEngineException.class);

        assertThat(logEvents.list).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(TOKEN).doesNotContain("Bearer").doesNotContain("hrva_landings").doesNotContain("SELECT"));
    }

    private void answer(final int status, final String body) {
        server.createContext(SQL_PATH, exchange -> {
            record(exchange);
            send(exchange, status, body);
        });
    }

    private void record(final HttpExchange exchange) throws IOException {
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private static void send(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (final var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
        exchange.close();
    }

    private static void sleep(final Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record Received(String method, String path, String authorization, String body) {
    }

}
