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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirusTotalClientTest {

    private static final String KEY = "vt-test-key-S3CRET-4b19";
    private static final String URLS = "/api/v3/urls";
    private static final String TARGET = "https://findfavoritedate.life/?u=f6yp608&s=~~?";
    private static final String TARGET_BASE64_ID = "aHR0cHM6Ly9maW5kZmF2b3JpdGVkYXRlLmxpZmUvP3U9ZjZ5cDYwOCZzPX5-Pw";
    private static final String SUBMITTED_SHA256 = "0f115db062b7c0dd030b16878c99dea5c354b49dc37b38eb8846179c7783e9d7";
    private static final String SUBMIT_ANSWER = "{\"data\": {\"type\": \"analysis\", \"id\": \"u-" + SUBMITTED_SHA256 + "-e1489251\"}}";
    private static final String TARGET_SHA256 = "25793058843bded85280e1a10ec107cea5b9bbd03b7d83c77e01e119c472cb80";
    private static final String REPORT_ID = "1db0ad7dbcec0676710ea0eaacd35d5e471d3e11944d53bcbd31f0cbd11bce31";
    private static final String FOUND = """
            {"data": {"id": "%s", "type": "url", "attributes": {
              "last_analysis_date": 1762163100,
              "last_analysis_stats": {"malicious": 7, "suspicious": 2, "undetected": 25, "harmless": 61, "timeout": 0},
              "url": "https://findfavoritedate.life/?u=f6yp608&s=~~?"}}}
            """.formatted(REPORT_ID);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties appProperties = new AppProperties();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(VirusTotalClient.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    private ExecutorService executor;
    private HttpServer server;
    private VirusTotalClient client;
    private Level previousLevel;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.start();
        appProperties.getInspection().setVirusTotalApiBase("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3");
        appProperties.getInspection().setVirusTotalApiKey(KEY);
        client = new VirusTotalClient(appProperties, jsonMapper, Duration.ofSeconds(2));
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
    void lookupUsesTheSha256OfTheUrl() {
        answer(200, FOUND);

        client.lookup(TARGET);
        client.lookup("https://radarkobiet.pl/link/2994/95993394");

        assertThat(received).extracting(Received::method).containsOnly("GET");
        assertThat(received).extracting(Received::path).containsExactly(URLS + "/" + TARGET_SHA256,
                URLS + "/3809527bd834fe2d1466b9dab8a1fec976426cf1cba58e5bbb55010c412cd889");
        assertThat(received).extracting(Received::apiKey).containsOnly(KEY);
        assertThat(received).extracting(Received::body).containsOnly("");
    }

    @Test
    void aLookupNeverCarriesTheUrl() {
        answer(200, FOUND);

        client.lookup(TARGET);

        final var encoded = URLEncoder.encode(TARGET, StandardCharsets.UTF_8);
        final var standardBase64 = Base64.getEncoder().encodeToString(TARGET.getBytes(StandardCharsets.UTF_8));
        assertThat(received).singleElement().satisfies(request -> assertThat(List.of(request.path(), String.valueOf(request.query()), request.body(),
                request.headers())).allSatisfy(part -> assertThat(part)
                .doesNotContain(TARGET)
                .doesNotContain(encoded)
                .doesNotContain(TARGET_BASE64_ID)
                .doesNotContain(standardBase64)
                .doesNotContain(standardBase64.replace("=", ""))
                .doesNotContain("findfavoritedate")));
    }

    @Test
    void foundReadsStatsAndDate() {
        answer(200, FOUND);

        assertThat(client.lookup(TARGET)).isEqualTo(new VirusTotalReport(VirusTotalReport.Outcome.FOUND, REPORT_ID, 7, 2, 61, 25,
                LocalDateTime.of(2025, 11, 3, 9, 45), null));
    }

    @Test
    void aReportWithoutAnAnalysisIsNotFound() {
        answer(200, "{\"data\": {\"id\": \"" + REPORT_ID + "\", \"type\": \"url\", \"attributes\": {\"url\": \"" + TARGET + "\"}}}");

        assertThat(client.lookup(TARGET).outcome()).isEqualTo(VirusTotalReport.Outcome.NOT_FOUND);
    }

    @Test
    void notFound404() {
        answer(404, "{\"error\": {\"code\": \"NotFoundError\", \"message\": \"URL not found\"}}");

        assertThat(client.lookup(TARGET)).isEqualTo(VirusTotalReport.notFound());
    }

    @Test
    void rateLimited429() {
        answer(429, "{\"error\": {\"code\": \"QuotaExceededError\", \"message\": \"Quota exceeded\"}}");

        assertThat(client.lookup(TARGET)).isEqualTo(VirusTotalReport.rateLimited());
        assertThat(client.submit(TARGET)).isEqualTo(VirusTotalReport.rateLimited());
    }

    @Test
    void badKey401() {
        answer(401, "{\"error\": {\"code\": \"WrongCredentialsError\", \"message\": \"Wrong API key\"}}");

        assertThat(client.lookup(TARGET)).isEqualTo(VirusTotalReport.keyRefused());
        assertThat(client.submit(TARGET)).isEqualTo(VirusTotalReport.keyRefused());
        assertThat(VirusTotalReport.keyRefused().failure()).isEqualTo("VirusTotal refused the API key.");
    }

    @Test
    void aForbiddenKeyIsRefusedToo() {
        answer(403, "{\"error\": {\"code\": \"ForbiddenError\"}}");

        assertThat(client.lookup(TARGET)).isEqualTo(VirusTotalReport.keyRefused());
    }

    @Test
    void anotherClientErrorIsARefusal() {
        answer(400, "{\"error\": {\"code\": \"InvalidArgumentError\"}}");

        assertThat(client.lookup(TARGET)).isEqualTo(VirusTotalReport.failed("VirusTotal refused the request (400)."));
        assertThat(client.submit(TARGET)).isEqualTo(VirusTotalReport.failed("VirusTotal refused the request (400)."));
    }

    @Test
    void aServerErrorIsUnavailable() {
        answer(503, "{\"error\": {\"code\": \"TransientError\"}}");

        assertThat(client.lookup(TARGET)).isEqualTo(VirusTotalReport.unavailable("VirusTotal answered with an error (503)."));
    }

    @Test
    void anUnreadableAnswerIsUnavailable() {
        answer(200, "<html>maintenance</html>");

        assertThat(client.lookup(TARGET)).isEqualTo(VirusTotalReport.unavailable("VirusTotal answered with an error (200)."));
    }

    @Test
    void timeoutFailsWithAReason() {
        server.createContext(URLS, exchange -> sleep(Duration.ofSeconds(5)));

        final var started = System.nanoTime();
        final var report = client.lookup(TARGET);

        assertThat(report).isEqualTo(VirusTotalReport.unavailable("VirusTotal didn't answer."));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void aClosedPortFailsWithTheSameReason() throws IOException {
        appProperties.getInspection().setVirusTotalApiBase("http://127.0.0.1:" + closedPort() + "/api/v3");

        assertThat(client.lookup(TARGET)).isEqualTo(VirusTotalReport.unavailable("VirusTotal didn't answer."));
    }

    @Test
    void submitPostsTheUrlAsAForm() {
        answer(200, SUBMIT_ANSWER);

        final var report = client.submit(TARGET);

        assertThat(report).isEqualTo(VirusTotalReport.submitted(SUBMITTED_SHA256));
        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(URLS);
            assertThat(request.apiKey()).isEqualTo(KEY);
            assertThat(request.contentType()).isEqualTo("application/x-www-form-urlencoded");
            assertThat(request.body()).isEqualTo("url=https%3A%2F%2Ffindfavoritedate.life%2F%3Fu%3Df6yp608%26s%3D%7E%7E%3F");
        });
    }

    @Test
    void aSendKeepsTheUrlIdentifierFromVirusTotalsAnalysisId() {
        answer(200, SUBMIT_ANSWER);

        assertThat(client.submit(TARGET)).isEqualTo(VirusTotalReport.submitted(SUBMITTED_SHA256));
    }

    @Test
    void aSendWhoseAnalysisIdDoesNotParseKeepsNoIdentifier() {
        final var answers = List.of("u-" + TARGET_SHA256.toUpperCase() + "-1762163100", "u-" + TARGET_SHA256 + "-", "u-" + TARGET_SHA256.substring(1)
                + "-1762163100", "f-" + TARGET_SHA256 + "-1762163100", "u-" + TARGET_SHA256 + "-1762163100/x", "u-" + TARGET_SHA256 + "-e148925g", "");
        for (final var analysisId : answers) {
            answer(200, "{\"data\": {\"type\": \"analysis\", \"id\": \"" + analysisId + "\"}}");
            assertThat(client.submit(TARGET)).isEqualTo(VirusTotalReport.submitted(null));
            received.clear();
        }
        answer(200, "{\"data\": {\"type\": \"analysis\"}}");
        assertThat(client.submit(TARGET)).isEqualTo(VirusTotalReport.submitted(null));
    }

    @Test
    void aLookupByVirusTotalsIdentifierAsksForItAsIs() {
        answer(200, FOUND);

        assertThat(client.lookUpByIdentifier(REPORT_ID).outcome()).isEqualTo(VirusTotalReport.Outcome.FOUND);

        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo(URLS + "/" + REPORT_ID);
            assertThat(request.apiKey()).isEqualTo(KEY);
        });
    }

    @Test
    void aLookupByAnythingButAnIdentifierIsRefusedWithoutACall() {
        answer(200, FOUND);

        for (final var identifier : List.of(TARGET, "../users", REPORT_ID.toUpperCase(), REPORT_ID + "0", "")) {
            assertThatThrownBy(() -> client.lookUpByIdentifier(identifier)).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(received).isEmpty();
    }

    @Test
    void withoutAKeyNoCallIsMade() {
        answer(200, FOUND);
        appProperties.getInspection().setVirusTotalApiKey(" ");

        assertThat(client.lookup(TARGET)).isEqualTo(VirusTotalReport.notConfigured());
        assertThat(client.submit(TARGET)).isEqualTo(VirusTotalReport.notConfigured());
        appProperties.getInspection().setVirusTotalApiKey(null);
        assertThat(client.lookup(TARGET)).isEqualTo(VirusTotalReport.notConfigured());

        assertThat(received).isEmpty();
    }

    @Test
    void theKeyAndTheLinkAreNeverLogged() {
        answer(401, "{}");
        client.lookup(TARGET);
        answer(200, FOUND);
        client.lookup(TARGET);
        client.lookUpByIdentifier(TARGET_SHA256);
        answer(200, "{\"data\": {\"type\": \"analysis\", \"id\": \"u-" + TARGET_SHA256 + "-1762163100\"}}");
        client.submit(TARGET);

        assertThat(logEvents.list).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(KEY)
                .doesNotContain("findfavoritedate")
                .doesNotContain(TARGET_SHA256)
                .doesNotContain(TARGET_BASE64_ID));
    }

    private void answer(final int status, final String body) {
        try {
            server.removeContext(URLS);
        } catch (final IllegalArgumentException exception) {
            assertThat(received).isEmpty();
        }
        server.createContext(URLS, exchange -> {
            record(exchange);
            send(exchange, status, body);
        });
    }

    private void record(final HttpExchange exchange) throws IOException {
        final var headers = exchange.getRequestHeaders();
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(),
                headers.getFirst("x-apikey"), headers.getFirst("Content-Type"), headers.toString(),
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

    private record Received(String method, String path, String query, String apiKey, String contentType, String headers, String body) {
    }

}
