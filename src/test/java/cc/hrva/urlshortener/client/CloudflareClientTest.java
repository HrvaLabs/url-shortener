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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CloudflareClientTest {

    private static final String TOKEN = "cf-test-token-S3CRET-4f9a";
    private static final String ZONE = "zone-1";
    private static final String HOSTNAMES = "/client/v4/zones/zone-1/custom_hostnames";
    private static final String BODY_MARKER = "BODY-MARKER-7731";
    private static final String HOSTNAME_ID = "0d89c70d-ad9f-4843-b99f-6cc0252067e9";
    private static final String HOSTNAME_JSON = """
            {"id":"0d89c70d-ad9f-4843-b99f-6cc0252067e9","hostname":"go.example.com","status":"pending",
             "ssl":{"id":"ssl-1","type":"dv","method":"http","status":"pending_validation",
                    "validation_errors":[{"message":"caa error: pki.goog not allowed"},{"message":"second"}]},
             "verification_errors":["custom hostname does not CNAME to this zone."],
             "ownership_verification":{"type":"txt","name":"_cf-custom-hostname.go.example.com","value":"abc"},
             "created_at":"2026-10-29T10:00:00Z"}
            """;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties appProperties = new AppProperties();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(CloudflareClient.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    private ExecutorService executor;
    private HttpServer server;
    private String baseUrl;
    private CloudflareClient client;
    private Level previousLevel;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        appProperties.getCloudflare().setApiBase(baseUrl + "/client/v4");
        appProperties.getCloudflare().setApiToken(TOKEN);
        appProperties.getCloudflare().setZoneId(ZONE);
        client = new CloudflareClient(appProperties, jsonMapper, Duration.ofSeconds(2));
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
    void createSendsHttpDcvOnly() {
        answer(HOSTNAMES, 201, envelope(HOSTNAME_JSON));

        final var result = client.create("go.example.com");

        assertThat(result).isInstanceOfSatisfying(CloudflareResult.Ok.class,
                ok -> assertThat(ok.value()).isEqualTo(new CloudflareHostname(HOSTNAME_ID, "go.example.com", "pending", "pending_validation",
                        List.of("custom hostname does not CNAME to this zone."), List.of("caa error: pki.goog not allowed", "second"))));
        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(HOSTNAMES);
            assertThat(request.body()).isEqualTo("{\"hostname\":\"go.example.com\",\"ssl\":{\"method\":\"http\",\"type\":\"dv\"}}");
            assertThat(request.body()).doesNotContain("custom_metadata");
            assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(request.contentType()).isEqualTo("application/json");
            assertThat(request.userAgent()).isEqualTo("hrva.cc-domains/1.0 (+https://hrva.cc/.well-known/security.txt)");
        });
    }

    @Test
    void listReadsEveryFieldAndTheTotal() {
        final var second = """
                {"id":"b0361ed9-412c-4eb2-b605-f300f945994b","hostname":"test.karlo.cc","status":"active",
                 "ssl":{"status":"active"}}
                """;
        answer(HOSTNAMES, 200, "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":[" + HOSTNAME_JSON + "," + second + "],"
                + "\"result_info\":{\"page\":2,\"per_page\":50,\"count\":2,\"total_count\":52,\"total_pages\":2}}");

        final var result = client.list(2);

        assertThat(result).isEqualTo(new CloudflareResult.Ok<>(List.of(
                new CloudflareHostname(HOSTNAME_ID, "go.example.com", "pending", "pending_validation",
                        List.of("custom hostname does not CNAME to this zone."), List.of("caa error: pki.goog not allowed", "second")),
                new CloudflareHostname("b0361ed9-412c-4eb2-b605-f300f945994b", "test.karlo.cc", "active", "active", List.of(), List.of())), 52));
        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.query()).contains("per_page=50").contains("page=2");
            assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
        });
    }

    @Test
    void totalCountReadsTheTotalFromASmallPage() {
        answer(HOSTNAMES, 200, "{\"success\":true,\"result\":[" + HOSTNAME_JSON + "],\"result_info\":{\"page\":1,\"per_page\":5,\"count\":1,\"total_count\":7}}");

        assertThat(client.totalCount()).isEqualTo(new CloudflareResult.Ok<>(7, 7));
        assertThat(received).singleElement().satisfies(request -> assertThat(request.query()).isEqualTo("per_page=5"));
    }

    @Test
    void findByHostnameFiltersByHostname() {
        answer(HOSTNAMES, 200, "{\"success\":true,\"result\":[" + HOSTNAME_JSON + "],\"result_info\":{\"total_count\":1}}");

        final var result = client.findByHostname("go.example.com");

        assertThat(result).isInstanceOfSatisfying(CloudflareResult.Ok.class,
                ok -> assertThat(ok.value()).isEqualTo(Optional.of(new CloudflareHostname(HOSTNAME_ID, "go.example.com", "pending", "pending_validation",
                        List.of("custom hostname does not CNAME to this zone."), List.of("caa error: pki.goog not allowed", "second")))));
        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo(HOSTNAMES);
            assertThat(request.query()).isEqualTo("hostname=go.example.com");
        });
    }

    @Test
    void findByHostnameIsEmptyWhenNothingMatchesExactly() {
        answer(HOSTNAMES, 200, "{\"success\":true,\"result\":[" + HOSTNAME_JSON + "],\"result_info\":{\"total_count\":1}}");

        assertThat(client.findByHostname("o.example.com")).isEqualTo(new CloudflareResult.Ok<>(Optional.empty(), 1));
    }

    @Test
    void getReadsOneHostname() {
        answer(HOSTNAMES + "/" + HOSTNAME_ID, 200, envelope(HOSTNAME_JSON));

        assertThat(client.get(HOSTNAME_ID)).isInstanceOfSatisfying(CloudflareResult.Ok.class,
                ok -> assertThat(((CloudflareHostname) ok.value()).status()).isEqualTo("pending"));
        assertThat(received).singleElement().satisfies(request -> assertThat(request.method()).isEqualTo("GET"));
    }

    @Test
    void deleteOfAMissingHostnameIsOk() {
        answer(HOSTNAMES + "/" + HOSTNAME_ID, 404, error(1436, "The custom hostname was not found."));

        assertThat(client.delete(HOSTNAME_ID)).isEqualTo(new CloudflareResult.Ok<Void>(null, null));
        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).isEqualTo(HOSTNAMES + "/" + HOSTNAME_ID);
        });
    }

    @Test
    void deleteIsOk() {
        answer(HOSTNAMES + "/" + HOSTNAME_ID, 200, envelope("{\"id\":\"" + HOSTNAME_ID + "\"}"));

        assertThat(client.delete(HOSTNAME_ID)).isEqualTo(new CloudflareResult.Ok<Void>(null, null));
    }

    @Test
    void getOfAMissingHostnameIsNotFound() {
        answer(HOSTNAMES + "/" + HOSTNAME_ID, 404, error(1436, "The custom hostname was not found."));

        assertThat(client.get(HOSTNAME_ID)).isEqualTo(new CloudflareResult.NotFound<>());
    }

    @Test
    void refusalsCarryTheCodeAndMessage() {
        answer(HOSTNAMES, 403, "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error\"}],\"messages\":[],\"result\":null}");

        assertThat(client.list(1)).isEqualTo(new CloudflareResult.Refused<>(403, 10000, "Authentication error"));
        assertThat(client.create("go.example.com")).isEqualTo(new CloudflareResult.Refused<>(403, 10000, "Authentication error"));
    }

    @Test
    void aDuplicateCreateIsARefusalWithItsCode() {
        answer(HOSTNAMES, 409, error(1406, "Duplicate custom hostname found."));

        assertThat(client.create("go.example.com")).isEqualTo(new CloudflareResult.Refused<>(409, 1406, "Duplicate custom hostname found."));
    }

    @Test
    void aSuccessFalseEnvelopeIsARefusal() {
        answer(HOSTNAMES, 200, error(1000, "Something odd"));

        assertThat(client.list(1)).isEqualTo(new CloudflareResult.Refused<>(200, 1000, "Something odd"));
    }

    @Test
    void anUnreadableRefusalHasNoCode() {
        answer(HOSTNAMES, 400, "not json");

        assertThat(client.list(1)).isEqualTo(new CloudflareResult.Refused<>(400, 0, null));
    }

    @Test
    void anUnreadableSuccessIsUnreachable() {
        answer(HOSTNAMES, 200, "<html>gateway</html>");

        assertThat(client.list(1)).isEqualTo(new CloudflareResult.Unreachable<>("unreadable"));
    }

    @Test
    void timeoutsAndConnectionFailuresAreUnreachable() throws IOException {
        server.createContext(HOSTNAMES, exchange -> sleep(Duration.ofSeconds(5)));

        final var started = System.nanoTime();
        final var silent = client.list(1);

        assertThat(silent).isEqualTo(new CloudflareResult.Unreachable<>("timeout"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));

        appProperties.getCloudflare().setApiBase("http://127.0.0.1:" + closedPort() + "/client/v4");
        assertThat(client.list(1)).isEqualTo(new CloudflareResult.Unreachable<>("error"));
    }

    @Test
    void fiveHundredIsUnreachable() {
        answer(HOSTNAMES, 500, error(1500, "Internal Server Error"));
        answer(HOSTNAMES + "/" + HOSTNAME_ID, 503, "");

        assertThat(client.list(1)).isEqualTo(new CloudflareResult.Unreachable<>("server error"));
        assertThat(client.get(HOSTNAME_ID)).isEqualTo(new CloudflareResult.Unreachable<>("server error"));
    }

    @Test
    void tooManyRequestsIsUnreachable() {
        answer(HOSTNAMES, 429, error(10429, "Rate limited"));

        assertThat(client.list(1)).isEqualTo(new CloudflareResult.Unreachable<>("rate limited"));
    }

    @Test
    void noRedirectIsFollowed() {
        answer("/elsewhere", 200, envelope(HOSTNAME_JSON));
        server.createContext(HOSTNAMES, exchange -> {
            record(exchange);
            exchange.getResponseHeaders().add("Location", baseUrl + "/elsewhere");
            send(exchange, 302, "");
        });

        assertThat(client.list(1)).isEqualTo(new CloudflareResult.Unreachable<>("redirected"));
        assertThat(received).extracting(Received::path).containsExactly(HOSTNAMES);
    }

    @Test
    void verifyTokenIsTrueOnlyWhenActive() {
        answer("/client/v4/user/tokens/verify", 200, envelope("{\"id\":\"t1\",\"status\":\"active\"}"));
        assertThat(client.verifyToken()).isEqualTo(new CloudflareResult.Ok<>(true, null));

        server.removeContext("/client/v4/user/tokens/verify");
        answer("/client/v4/user/tokens/verify", 200, envelope("{\"id\":\"t1\",\"status\":\"disabled\"}"));
        assertThat(client.verifyToken()).isEqualTo(new CloudflareResult.Ok<>(false, null));

        server.removeContext("/client/v4/user/tokens/verify");
        answer("/client/v4/user/tokens/verify", 401, error(1000, "Invalid API Token"));
        assertThat(client.verifyToken()).isEqualTo(new CloudflareResult.Refused<>(401, 1000, "Invalid API Token"));
        assertThat(received).extracting(Received::authorization).containsOnly("Bearer " + TOKEN);
    }

    @Test
    void quotaReadsUsedAndAllocated() {
        answer(HOSTNAMES + "/quota", 200, envelope("{\"allocated\":50000,\"used\":4,\"exceeded\":false,\"hard_cap\":100000}"));

        assertThat(client.quota()).isEqualTo(new CloudflareResult.Ok<>(new CloudflareClient.Quota(4, 50000), null));
    }

    @Test
    void aTrailingSlashInTheApiBaseIsIgnored() {
        appProperties.getCloudflare().setApiBase(baseUrl + "/client/v4/");
        answer(HOSTNAMES + "/quota", 200, envelope("{\"allocated\":50000,\"used\":4}"));

        assertThat(client.quota()).isEqualTo(new CloudflareResult.Ok<>(new CloudflareClient.Quota(4, 50000), null));
        assertThat(received).extracting(Received::path).containsExactly(HOSTNAMES + "/quota");
    }

    @Test
    void theHostnameQueryValueIsEncoded() {
        answer(HOSTNAMES, 200, "{\"success\":true,\"result\":[],\"result_info\":{\"total_count\":0}}");

        client.findByHostname("go.example.com&per_page=1000");

        assertThat(received).singleElement().satisfies(request -> assertThat(request.rawQuery()).isEqualTo("hostname=go.example.com%26per_page%3D1000"));
    }

    @Test
    void nothingIsSentWithoutATokenOrZone() {
        answer(HOSTNAMES, 200, envelope(HOSTNAME_JSON));
        appProperties.getCloudflare().setApiToken(" ");

        assertThat(client.create("go.example.com")).isEqualTo(new CloudflareResult.Unreachable<>("not configured"));
        assertThat(client.verifyToken()).isEqualTo(new CloudflareResult.Unreachable<>("not configured"));

        appProperties.getCloudflare().setApiToken(TOKEN);
        appProperties.getCloudflare().setZoneId(null);
        assertThat(client.list(1)).isEqualTo(new CloudflareResult.Unreachable<>("not configured"));
        assertThat(received).isEmpty();
    }

    @Test
    void theTokenIsNeverLogged() throws IOException {
        answer(HOSTNAMES, 403, "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error " + BODY_MARKER + "\"}]}");
        answer(HOSTNAMES + "/quota", 500, BODY_MARKER);
        answer("/client/v4/user/tokens/verify", 401, BODY_MARKER);
        answer(HOSTNAMES + "/" + HOSTNAME_ID, 404, BODY_MARKER);
        server.createContext(HOSTNAMES + "/slow", exchange -> sleep(Duration.ofSeconds(5)));
        server.createContext(HOSTNAMES + "/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/" + TOKEN);
            send(exchange, 302, BODY_MARKER);
        });

        final var results = new java.util.ArrayList<CloudflareResult<?>>();
        results.add(client.list(1));
        results.add(client.create("go.example.com"));
        results.add(client.quota());
        results.add(client.verifyToken());
        results.add(client.get("slow"));
        results.add(client.get("moved"));
        results.add(client.get(HOSTNAME_ID));
        appProperties.getCloudflare().setApiToken("bad\ntoken-" + TOKEN);
        results.add(client.totalCount());
        appProperties.getCloudflare().setApiToken(TOKEN);
        appProperties.getCloudflare().setApiBase("http://127.0.0.1:" + closedPort() + "/client/v4");
        results.add(client.delete(HOSTNAME_ID));

        assertThat(results).allSatisfy(result -> assertThat(result.toString()).doesNotContain(TOKEN));
        assertThat(results.get(7)).isEqualTo(new CloudflareResult.Unreachable<>("invalid"));
        assertThat(logEvents.list).hasSizeGreaterThanOrEqualTo(8);
        assertThat(logEvents.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(TOKEN).doesNotContain("Bearer").doesNotContain(BODY_MARKER);
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(logEvents.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(line -> assertThat(line).contains("call=list").contains("status=403").contains("code=10000"));
    }

    private void answer(final String path, final int status, final String body) {
        server.createContext(path, exchange -> {
            record(exchange);
            send(exchange, status, body);
        });
    }

    private void record(final HttpExchange exchange) throws IOException {
        final var headers = exchange.getRequestHeaders();
        final var uri = exchange.getRequestURI();
        received.add(new Received(exchange.getRequestMethod(), uri.getPath(), uri.getQuery(), uri.getRawQuery(),
                headers.getFirst("Authorization"), headers.getFirst("Content-Type"), headers.getFirst("User-Agent"),
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

    private static String envelope(final String result) {
        return "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":" + result + "}";
    }

    private static String error(final int code, final String message) {
        return "{\"success\":false,\"errors\":[{\"code\":" + code + ",\"message\":\"" + message + "\"}],\"messages\":[],\"result\":null}";
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

    private record Received(String method, String path, String query, String rawQuery, String authorization, String contentType,
                            String userAgent, String body) {
    }

}
