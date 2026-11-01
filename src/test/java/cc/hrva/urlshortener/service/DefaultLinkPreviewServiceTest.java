package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.configuration.PublicHttpFetcherConfiguration;
import cc.hrva.urlshortener.dto.LinkPreviewResponse;
import cc.hrva.urlshortener.service.impl.DefaultLinkPreviewService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLinkPreviewServiceTest {

    private static final String PAGE = """
            <html><head>
            <title>Fallback title</title>
            <meta property="og:title" content="Ćevapi u Zagrebu">
            <meta name="description" content="Gdje jesti">
            <meta property="og:image" content="https://cdn.example/cevapi.jpg">
            </head><body></body></html>""";

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final PublicHttpFetcher loopbackFetcher = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly().or(this::isLocal),
            InetAddress::isLoopbackAddress);
    private ExecutorService executor;
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().equals("/moved")) {
                exchange.getResponseHeaders().add("Location", "/article");
                exchange.sendResponseHeaders(301, -1);
                exchange.close();
                return;
            }
            send(exchange, PAGE);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void readsTheOpenGraphTagsOfThePageThroughThePinnedFetcher() {
        final var service = new DefaultLinkPreviewService((url, maxBytes, timeout) -> loopbackFetcher.fetch(url, maxBytes, null, null, timeout));

        assertThat(service.fetchPreview(baseUrl + "/moved"))
                .isEqualTo(new LinkPreviewResponse(baseUrl + "/moved", "Ćevapi u Zagrebu", "Gdje jesti", "https://cdn.example/cevapi.jpg"));
        assertThat(requests).containsExactly("/moved", "/article");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://localhost:8080/actuator",
            "http://10.1.2.3/",
            "http://192.168.1.1/admin",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/",
            "http://[fd00::1]/",
            "ftp://1.1.1.1/file",
            "file:///etc/passwd",
            "not a url"
    })
    void returnsAnEmptyPreviewForATargetThatIsNotAPublicWebAddress(final String url) {
        final var service = new DefaultLinkPreviewService(new PublicHttpFetcherConfiguration().pageFetcher());

        assertThat(service.fetchPreview(url)).isEqualTo(new LinkPreviewResponse(url, null, null, null));
    }

    @Test
    void theApplicationPreviewFetcherNeverRequestsALoopbackServer() {
        final var service = new DefaultLinkPreviewService(new PublicHttpFetcherConfiguration().pageFetcher());

        assertThat(service.fetchPreview(baseUrl + "/article")).isEqualTo(new LinkPreviewResponse(baseUrl + "/article", null, null, null));
        assertThat(requests).isEmpty();
    }

    private boolean isLocal(final String url) {
        return url.startsWith(baseUrl);
    }

    private static void send(final HttpExchange exchange, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (final var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

}
