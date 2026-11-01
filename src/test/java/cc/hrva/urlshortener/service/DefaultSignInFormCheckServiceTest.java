package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.Resolver;
import cc.hrva.urlshortener.client.ScriptedFetchers;
import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.configuration.PublicHttpFetcherConfiguration;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultSignInFormCheckService;
import cc.hrva.urlshortener.util.PublicAddresses;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultSignInFormCheckServiceTest {

    private static final long LINK_ID = 41L;
    private static final Predicate<InetAddress> LOOPBACK_OR_PUBLIC =
            address -> InetAddress.getLoopbackAddress().equals(address) || PublicAddresses.isPublicAddress(address);
    private static final String DAVIVIENDA_CLONE = """
            <html><head><title>Davivienda</title></head><body>
            <form method="post" action="/cache-trash/acceso">
              <label for="tipo">Tipo de documento</label><select id="tipo"><option>Cédula</option></select>
              <label for="numero">Identificación</label><input id="numero" type="text" inputmode="numeric">
              <button type="submit">Ingresar</button>
            </form></body></html>""";
    private static final String PASSWORD_PAGE = "<html><body><div><input name=\"u\"><input type=\"password\" name=\"p\"></div></body></html>";
    private static final String ARTICLE = """
            <html><body><form role="search"><input name="q" placeholder="Search"><button>Go</button></form>
            <article><h1>How to pick a password manager</h1><p>Long text.</p></article></body></html>""";

    private final UrlRepository urlRepository = mock(UrlRepository.class);
    private final ModerationService moderationService = mock(ModerationService.class);
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> headers = new ConcurrentHashMap<>();
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(DefaultSignInFormCheckService.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private ExecutorService executor;
    private HttpServer server;
    private int port;
    private DefaultSignInFormCheckService service;

    @BeforeEach
    void setUp() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            headers.put("User-Agent", exchange.getRequestHeaders().getFirst("User-Agent"));
            headers.put("Accept-Language", exchange.getRequestHeaders().getFirst("Accept-Language"));
            switch (exchange.getRequestURI().getPath()) {
                case "/cache-trash/" -> send(exchange, 200, DAVIVIENDA_CLONE);
                case "/password" -> send(exchange, 200, PASSWORD_PAGE);
                case "/article" -> send(exchange, 200, ARTICLE);
                case "/huge" -> send(exchange, 200, PASSWORD_PAGE + "<!--" + "x".repeat(600 * 1024) + "-->");
                case "/moved" -> {
                    exchange.getResponseHeaders().add("Location", "/password");
                    send(exchange, 302, "");
                }
                case "/to-internal" -> {
                    exchange.getResponseHeaders().add("Location", "http://internal.test:" + port + "/password");
                    send(exchange, 302, "");
                }
                default -> send(exchange, 500, "");
            }
        });
        server.start();
        port = server.getAddress().getPort();
        final Resolver resolver = host -> new InetAddress[] {"internal.test".equals(host)
                ? InetAddress.getByAddress(host, new byte[] {10, 0, 0, 5})
                : InetAddress.getLoopbackAddress()};
        final var fetcher = ScriptedFetchers.resolvingWith(resolver, LOOPBACK_OR_PUBLIC);
        service = new DefaultSignInFormCheckService(urlRepository,
                (url, maxBytes, timeout) -> fetcher.fetchFirst(url, maxBytes, PublicHttpFetcherConfiguration.BROWSER_HEADERS, timeout), moderationService);
        when(moderationService.holdForSignInForm(anyLong(), anyString(), anyString())).thenReturn(true);
        logEvents.start();
        serviceLogger.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logEvents);
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void aPageWithAPasswordFieldIsHeldAfterItsRedirect() {
        final var url = warningPageLink("http://shop.test:" + port + "/moved");

        assertThat(service.check(LINK_ID)).isTrue();

        verify(moderationService).holdForSignInForm(LINK_ID, url, "a password field");
        assertThat(requests).containsExactly("/moved", "/password");
    }

    @Test
    void theBankCloneWithAnIdentificationFieldIsHeldAndFetchedLikeABrowser() {
        final var url = warningPageLink("http://verniqindexone.test:" + port + "/cache-trash/");

        assertThat(service.check(LINK_ID)).isTrue();

        verify(moderationService).holdForSignInForm(LINK_ID, url, "a sign-in field (identificacion)");
        assertThat(headers).containsEntry("Accept-Language", "en-US,en;q=0.9");
        assertThat(headers.get("User-Agent")).startsWith("Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
    }

    @Test
    void aPageOverTheCapIsCheckedOnItsFirst512Kilobytes() {
        final var url = warningPageLink("http://shop.test:" + port + "/huge");

        assertThat(service.check(LINK_ID)).isTrue();

        verify(moderationService).holdForSignInForm(LINK_ID, url, "a password field");
    }

    @Test
    void aPlainArticleIsNotHeld() {
        warningPageLink("http://news.test:" + port + "/article");

        assertThat(service.check(LINK_ID)).isFalse();

        verify(moderationService, never()).holdForSignInForm(anyLong(), anyString(), anyString());
    }

    @Test
    void aFetchThatFailsHoldsNothingAndLogsOnlyTheDomain() throws IOException {
        warningPageLink("http://broken.test:" + port + "/secret-path?token=abc");
        warningPageLink(42L, "http://closed.test:" + closedPort() + "/secret-path?token=abc");

        assertThat(service.check(LINK_ID)).isFalse();
        assertThat(service.check(42L)).isFalse();

        verify(moderationService, never()).holdForSignInForm(anyLong(), anyString(), anyString());
        assertThat(logEvents.list).hasSize(2).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("domain=").doesNotContain("secret-path").doesNotContain("token"));
    }

    @Test
    void aRedirectToAPrivateAddressIsRefusedByThePinnedFetcher() {
        warningPageLink("http://shop.test:" + port + "/to-internal");

        assertThat(service.check(LINK_ID)).isFalse();

        assertThat(requests).containsExactly("/to-internal");
        verify(moderationService, never()).holdForSignInForm(anyLong(), anyString(), anyString());
    }

    @Test
    void aLinkWithAnOwnerWithoutTheWarningPageOrThatIsOffIsNeverFetched() {
        final var owned = warningPageLink(LINK_ID, "http://shop.test:" + port + "/password");
        owned.setOwner(User.builder().id(1L).build());
        final var plain = warningPageLink(43L, "http://shop.test:" + port + "/password");
        plain.setWarningPage(false);
        final var off = warningPageLink(44L, "http://shop.test:" + port + "/password");
        off.setActive(false);

        assertThat(service.check(LINK_ID)).isFalse();
        assertThat(service.check(43L)).isFalse();
        assertThat(service.check(44L)).isFalse();
        assertThat(service.check(45L)).isFalse();

        assertThat(requests).isEmpty();
    }

    @Test
    void theBackgroundCheckRunsOnTheRedirectCheckThreads() throws NoSuchMethodException {
        assertThat(DefaultSignInFormCheckService.class.getMethod("checkLater", long.class).getAnnotation(Async.class).value())
                .isEqualTo(AsyncExecutorConfiguration.REDIRECT_CHECK_EXECUTOR);
    }

    private String warningPageLink(final String longUrl) {
        warningPageLink(LINK_ID, longUrl);
        return longUrl;
    }

    private Url warningPageLink(final long id, final String longUrl) {
        final var url = Url.builder().id(id).shortUrl("s" + id).longUrl(longUrl).active(true).warningPage(true).build();
        when(urlRepository.findById(id)).thenReturn(Optional.of(url));
        return url;
    }

    private static int closedPort() throws IOException {
        try (final var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void send(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (final var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

}
