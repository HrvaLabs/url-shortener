package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.Resolver;
import cc.hrva.urlshortener.client.ScriptedFetchers;
import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.configuration.PublicHttpFetcherConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ThreatType;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultSignInFormCheckService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import cc.hrva.urlshortener.service.signin.NewAccounts;
import cc.hrva.urlshortener.service.signin.RealSignInHosts;
import cc.hrva.urlshortener.util.PublicAddresses;
import cc.hrva.urlshortener.util.ReviewReasons;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultSignInFormCheckServiceTest {

    private static final long LINK_ID = 41L;
    private static final Instant NOW = Instant.parse("2026-11-05T10:15:30Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final User NEW_ACCOUNT = User.builder().id(5L).createDate(NOW_LOCAL.minusDays(3)).build();
    private static final User OLDER_ACCOUNT = User.builder().id(6L).createDate(NOW_LOCAL.minusYears(1)).build();
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
    private final ReviewQueueService reviewQueueService = mock(ReviewQueueService.class);
    private final RealSignInHosts realSignInHosts = mock(RealSignInHosts.class);
    private final ScammerSignalService scammerSignalService = mock(ScammerSignalService.class);
    private final SettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());
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
                case "/to-phish" -> {
                    exchange.getResponseHeaders().add("Location", "http://phish.test:" + port + "/password");
                    send(exchange, 302, "");
                }
                case "/to-real" -> {
                    exchange.getResponseHeaders().add("Location", "http://accounts.real.test:" + port + "/password");
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
                (url, maxBytes, timeout) -> fetcher.fetchFirst(url, maxBytes, PublicHttpFetcherConfiguration.BROWSER_HEADERS, timeout), moderationService,
                reviewQueueService, realSignInHosts, new NewAccounts(settingsService, scammerSignalService, Clock.fixed(NOW, ZoneOffset.UTC)), settingsService,
                TransactionOperations.withoutTransaction());
        when(moderationService.holdForSignInForm(anyLong(), anyString(), anyString())).thenReturn(true);
        when(moderationService.holdOwnedLinkForSignInForm(anyLong(), anyString(), anyString(), anyString())).thenReturn(true);
        when(scammerSignalService.possibleScammers(anyCollection())).thenReturn(Set.of());
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

    @Test
    void aNewAccountsLinkToASignInFormIsHeld() {
        final var url = accountLink(LINK_ID, "http://shop.test:" + port + "/moved", NEW_ACCOUNT);

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.HELD);

        verify(moderationService).holdOwnedLinkForSignInForm(LINK_ID, url.getLongUrl(), "a password field", ReviewReasons.SIGN_IN_FORM_NEW_ACCOUNT);
        verifyNoInteractions(reviewQueueService);
        assertThat(requests).containsExactly("/moved", "/password");
        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Sign-in form on a link from a new account urlId=41 domain=shop.test held=true");
    }

    @Test
    void aLinkThatIsNoLongerHeldWhenTheCheckEndsIsSkipped() {
        accountLink(LINK_ID, "http://shop.test:" + port + "/password", NEW_ACCOUNT);
        when(moderationService.holdOwnedLinkForSignInForm(anyLong(), anyString(), anyString(), anyString())).thenReturn(false);

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.SKIPPED);

        verifyNoInteractions(reviewQueueService);
    }

    @Test
    void anOlderAccountsLinkGetsANoticeOnly() {
        final var url = accountLink(LINK_ID, "http://verniqindexone.test:" + port + "/cache-trash/", OLDER_ACCOUNT);

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.NOTICED);

        verify(reviewQueueService).notice(url, List.of(ReviewReasons.SIGN_IN_FORM_ACCOUNT));
        verify(moderationService, never()).holdOwnedLinkForSignInForm(anyLong(), anyString(), anyString(), anyString());
        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Sign-in form on a link from an account, a review notice is added urlId=41 domain=verniqindexone.test");
    }

    @Test
    void aPossibleScammerIsTreatedAsNew() {
        final var url = accountLink(LINK_ID, "http://shop.test:" + port + "/password", OLDER_ACCOUNT);
        when(scammerSignalService.possibleScammers(List.of(OLDER_ACCOUNT))).thenReturn(Set.of(OLDER_ACCOUNT.getId()));

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.HELD);

        verify(moderationService).holdOwnedLinkForSignInForm(LINK_ID, url.getLongUrl(), "a password field", ReviewReasons.SIGN_IN_FORM_NEW_ACCOUNT);
        verifyNoInteractions(reviewQueueService);
    }

    @Test
    void aFormOnARealSignInHostCountsForNothing() {
        accountLink(LINK_ID, "http://accounts.real.test:" + port + "/password", NEW_ACCOUNT);
        when(realSignInHosts.isRealSignInHost("accounts.real.test")).thenReturn(true);

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.REAL_SIGN_IN_PAGE);

        verify(moderationService, never()).holdOwnedLinkForSignInForm(anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(reviewQueueService);
    }

    @Test
    void aFormReachedThroughARedirectIsJudgedOnTheFinalHost() {
        final var throughARealHost = accountLink(LINK_ID, "http://accounts.real.test:" + port + "/to-phish", NEW_ACCOUNT);
        accountLink(42L, "http://phish.test:" + port + "/to-real", NEW_ACCOUNT);
        when(realSignInHosts.isRealSignInHost("accounts.real.test")).thenReturn(true);

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.HELD);
        assertThat(service.checkAccountLink(42L)).isEqualTo(AccountCheckOutcome.REAL_SIGN_IN_PAGE);

        verify(moderationService).holdOwnedLinkForSignInForm(LINK_ID, throughARealHost.getLongUrl(), "a password field",
                ReviewReasons.SIGN_IN_FORM_NEW_ACCOUNT);
        verify(moderationService, never()).holdOwnedLinkForSignInForm(eq(42L), anyString(), anyString(), anyString());
        verify(realSignInHosts).isRealSignInHost("phish.test");
        verify(realSignInHosts).isRealSignInHost("accounts.real.test");
        assertThat(requests).containsExactly("/to-phish", "/password", "/to-real", "/password");
    }

    @Test
    void aPageWithoutAFormDoesNothing() {
        accountLink(LINK_ID, "http://news.test:" + port + "/article", NEW_ACCOUNT);

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.NO_FORM);

        verify(moderationService, never()).holdOwnedLinkForSignInForm(anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(reviewQueueService, realSignInHosts);
    }

    @Test
    void aFailedFetchDoesNothing() {
        accountLink(LINK_ID, "http://broken.test:" + port + "/secret-path?token=abc", NEW_ACCOUNT);

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.FETCH_FAILED);

        verify(moderationService, never()).holdOwnedLinkForSignInForm(anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(reviewQueueService, realSignInHosts);
        assertThat(logEvents.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .startsWith("Could not check the page for a sign-in form, the link stays as it is urlId=41 domain=broken.test ")
                .doesNotContain("secret-path").doesNotContain("token"));
    }

    @Test
    void theSwitchOffSkipsAccountLinks() {
        accountLink(LINK_ID, "http://shop.test:" + port + "/password", NEW_ACCOUNT);
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.SIGN_IN_FORM_ACCOUNT_LINKS);

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.SKIPPED);

        assertThat(requests).isEmpty();
        verify(moderationService, never()).holdOwnedLinkForSignInForm(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void anAdminsLinkIsSkipped() {
        final var admin = User.builder().id(3L).createDate(NOW_LOCAL.minusDays(1)).twoStepEnabled(true)
                .authorities(List.of(Authorities.builder().name("ROLE_ADMIN").build())).build();
        accountLink(LINK_ID, "http://shop.test:" + port + "/password", admin);

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.SKIPPED);

        assertThat(requests).isEmpty();
        verify(moderationService, never()).holdOwnedLinkForSignInForm(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void aLinkThatIsGoneOffBlockedOrWithoutAnOwnerIsNeverFetched() {
        final var off = accountLink(LINK_ID, "http://shop.test:" + port + "/password", NEW_ACCOUNT);
        off.setActive(false);
        accountLink(43L, "http://shop.test:" + port + "/password", NEW_ACCOUNT).setBlockReason(BlockReason.SIGN_IN_FORM);
        accountLink(44L, "http://shop.test:" + port + "/password", NEW_ACCOUNT).setThreatType(ThreatType.MALWARE);
        accountLink(45L, "http://shop.test:" + port + "/password", null);

        assertThat(service.checkAccountLink(LINK_ID)).isEqualTo(AccountCheckOutcome.SKIPPED);
        assertThat(service.checkAccountLink(43L)).isEqualTo(AccountCheckOutcome.SKIPPED);
        assertThat(service.checkAccountLink(44L)).isEqualTo(AccountCheckOutcome.SKIPPED);
        assertThat(service.checkAccountLink(45L)).isEqualTo(AccountCheckOutcome.SKIPPED);
        assertThat(service.checkAccountLink(46L)).isEqualTo(AccountCheckOutcome.SKIPPED);

        assertThat(requests).isEmpty();
        verifyNoInteractions(reviewQueueService);
    }

    @Test
    void theAccountCheckRunsOnItsOwnThreads() throws NoSuchMethodException {
        assertThat(DefaultSignInFormCheckService.class.getMethod("checkAccountLinkLater", long.class).getAnnotation(Async.class).value())
                .isEqualTo(AsyncExecutorConfiguration.SIGN_IN_CHECK_EXECUTOR);
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

    private Url accountLink(final long id, final String longUrl, final User owner) {
        final var url = Url.builder().id(id).shortUrl("s" + id).longUrl(longUrl).active(true).owner(owner).build();
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
