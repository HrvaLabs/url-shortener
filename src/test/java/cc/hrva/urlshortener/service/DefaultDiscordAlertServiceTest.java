package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.DiscordMessage;
import cc.hrva.urlshortener.client.DiscordWebhookClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AlertedLink;
import cc.hrva.urlshortener.service.alert.DiscordAlertFormatter;
import cc.hrva.urlshortener.service.impl.DefaultDiscordAlertService;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultDiscordAlertServiceTest {

    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1234567890/s3cr3t-w3bh00k-t0k3n";
    private static final AdminAlertEvent NEW_USER = new AdminAlertEvent.UserSignedUp(31L, "local");
    private static final AdminAlertEvent NEW_REVIEW = new AdminAlertEvent.ReviewOpened(
            new AlertedLink(42L, "abc12", "example-login.top", null), List.of("imitates paypal", "domain registered in the last 30 days"));
    private static final AdminAlertEvent BLOCKED = new AdminAlertEvent.LinkBlocked(
            new AlertedLink(43L, "k3Xa1", "paypa1-login.top", 27L), BlockReason.BLOCKLIST, "HaGeZi TIF medium");

    @Mock
    private DiscordWebhookClient discordWebhookClient;

    private final AppProperties appProperties = new AppProperties();

    private DiscordAlertService discordAlertService;

    @BeforeEach
    void setUp() {
        appProperties.setFrontendUrl("https://app.hrva.cc");
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setDiscordWebhookUrl(WEBHOOK_URL);
        discordAlertService = service(discordWebhookClient);
        lenient().when(discordWebhookClient.send(anyString(), any(DiscordMessage.class), any(Duration.class))).thenReturn(true);
        lenient().when(discordWebhookClient.sendWithoutWaiting(anyString(), any(DiscordMessage.class), any(Duration.class))).thenReturn(true);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void withoutAWebhookUrlNothingIsBufferedOrSent(final String webhookUrl) {
        appProperties.setDiscordWebhookUrl(webhookUrl);

        discordAlertService.onAdminAlert(NEW_USER);
        discordAlertService.flush();
        appProperties.setDiscordWebhookUrl(WEBHOOK_URL);
        discordAlertService.flush();

        verifyNoInteractions(discordWebhookClient);
    }

    @Test
    void startupSaysOnceThatAlertsAreOffWithoutAWebhookUrl(final CapturedOutput output) {
        appProperties.setDiscordWebhookUrl("");

        ((DefaultDiscordAlertService) discordAlertService).logWhenOff();

        assertThat(output).containsOnlyOnce("Discord alerts are off: no DISCORD_WEBHOOK_URL");
    }

    @Test
    void startupSaysNothingWhenAlertsAreOn(final CapturedOutput output) {
        ((DefaultDiscordAlertService) discordAlertService).logWhenOff();

        assertThat(output).doesNotContain("Discord alerts are off");
    }

    @Test
    void aFlushSendsEverythingBufferedAsOneMessageWithATimeoutOfFiveSeconds() {
        discordAlertService.onAdminAlert(NEW_REVIEW);
        discordAlertService.onAdminAlert(BLOCKED);
        discordAlertService.onAdminAlert(NEW_USER);

        discordAlertService.flush();

        final var message = ArgumentCaptor.forClass(DiscordMessage.class);
        verify(discordWebhookClient).send(eq(WEBHOOK_URL), message.capture(), eq(Duration.ofSeconds(5)));
        assertThat(message.getValue().content().lines().toList()).containsExactly(
                "New review: hrva.cc/abc12 → example-login.top (imitates paypal, domain registered in the last 30 days), anonymous, "
                        + "<https://app.hrva.cc/admin/review?entry=42>",
                "Blocked: hrva.cc/k3Xa1 → paypa1-login.top (on HaGeZi TIF medium), account #27, <https://app.hrva.cc/admin/urls>",
                "New user: account #31 signed up with email, <https://app.hrva.cc/admin>");
    }

    @Test
    void aFlushWithNothingBufferedSendsNothing() {
        discordAlertService.onAdminAlert(NEW_USER);
        discordAlertService.flush();

        discordAlertService.flush();

        verify(discordWebhookClient, times(1)).send(anyString(), any(DiscordMessage.class), any(Duration.class));
    }

    @Test
    void aFullBufferDropsNewAlertsAndCountsThemInTheNextMessage() {
        IntStream.range(0, 205).forEach(index -> discordAlertService.onAdminAlert(NEW_USER));

        discordAlertService.flush();

        final var message = ArgumentCaptor.forClass(DiscordMessage.class);
        verify(discordWebhookClient).send(anyString(), message.capture(), any(Duration.class));
        final var lines = message.getValue().content().lines().toList();
        assertThat(lines).hasSize(11);
        assertThat(lines.getLast()).isEqualTo("…and 195 more, see <https://app.hrva.cc/admin/review>");
    }

    @Test
    void alertsThatDidNotFitAreNotCarriedIntoTheNextMessage() {
        IntStream.range(0, 15).forEach(index -> discordAlertService.onAdminAlert(NEW_USER));
        discordAlertService.flush();

        discordAlertService.flush();

        verify(discordWebhookClient, times(1)).send(anyString(), any(DiscordMessage.class), any(Duration.class));
    }

    @Test
    void aFailedDeliveryDropsTheBatchWithoutRetryingIt() {
        when(discordWebhookClient.send(anyString(), any(DiscordMessage.class), any(Duration.class))).thenReturn(false);
        discordAlertService.onAdminAlert(NEW_USER);
        discordAlertService.flush();

        discordAlertService.flush();

        verify(discordWebhookClient, times(1)).send(anyString(), any(DiscordMessage.class), any(Duration.class));
    }

    @Test
    void anUnexpectedFailureIsCaughtAndLoggedByClassOnly(final CapturedOutput output) {
        when(discordWebhookClient.send(anyString(), any(DiscordMessage.class), any(Duration.class)))
                .thenThrow(new IllegalStateException("failed calling " + WEBHOOK_URL));
        discordAlertService.onAdminAlert(NEW_USER);

        assertThatCode(discordAlertService::flush).doesNotThrowAnyException();

        assertThat(output).contains("Discord alerts dropped exception=java.lang.IllegalStateException")
                .doesNotContain("s3cr3t-w3bh00k-t0k3n");
    }

    @Test
    void anAlertPublishedInATransactionIsBufferedOnlyOnceItCommits() {
        try (final var context = listeningContext()) {
            final var transactions = new TransactionTemplate(new NoResourceTransactionManager());
            transactions.executeWithoutResult(status -> {
                context.publishEvent(NEW_USER);
                discordAlertService.flush();
                verifyNoInteractions(discordWebhookClient);
            });

            discordAlertService.flush();
        }

        verify(discordWebhookClient, times(1)).send(anyString(), any(DiscordMessage.class), any(Duration.class));
    }

    @Test
    void anAlertPublishedInATransactionThatRollsBackIsNeverSent() {
        try (final var context = listeningContext()) {
            new TransactionTemplate(new NoResourceTransactionManager()).executeWithoutResult(status -> {
                context.publishEvent(NEW_USER);
                status.setRollbackOnly();
            });

            discordAlertService.flush();
        }

        verifyNoInteractions(discordWebhookClient);
    }

    @Test
    void anAlertPublishedOutsideATransactionIsBufferedAtOnce() {
        try (final var context = listeningContext()) {
            context.publishEvent(BLOCKED);

            discordAlertService.flush();
        }

        verify(discordWebhookClient).send(anyString(), any(DiscordMessage.class), any(Duration.class));
    }

    @Test
    void publishedAlertsReachALocalWebhookAsOneMessage() throws IOException {
        final var bodies = new CopyOnWriteArrayList<String>();
        final ExecutorService executor = Executors.newCachedThreadPool();
        final var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/webhooks/1/token", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            appProperties.setDiscordWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/token");
            discordAlertService = service(new DiscordWebhookClient(JsonMapper.builder().build()));
            try (final var context = listeningContext()) {
                context.publishEvent(new AdminAlertEvent.VisitorReported(new AlertedLink(42L, "abc12", "example-login.top", null),
                        ReportCategory.PHISHING, "@everyone it asks for my card", false));
                context.publishEvent(new AdminAlertEvent.BlocklistSourceFailed("HaGeZi TIF medium", "HTTP 503", false));

                discordAlertService.flush();
            }
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }

        assertThat(bodies).hasSize(1);
        final var json = JsonMapper.builder().build().readTree(bodies.getFirst());
        assertThat(json.path("content").asString().lines().toList()).containsExactly(
                "Visitor report on an open review: hrva.cc/abc12 → example-login.top (phishing: \"@everyone it asks for my card\"), anonymous, "
                        + "<https://app.hrva.cc/admin/review?entry=42>",
                "HaGeZi TIF medium failed to load: HTTP 503, <https://app.hrva.cc/admin/blocklists>");
        assertThat(json.path("allowed_mentions").path("parse").isEmpty()).isTrue();
        assertThat(json.path("flags").asInt()).isEqualTo(4);
    }

    @Test
    void theBufferIsFlushedEveryThirtySeconds() throws NoSuchMethodException {
        final var scheduled = DefaultDiscordAlertService.class.getMethod("flush").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelay()).isEqualTo(Duration.ofSeconds(30).toMillis());
    }

    @Test
    void whatIsStillBufferedIsSentWhenTheAppShutsDownWithinFiveSecondsAndWithoutWaitingOutARateLimit() {
        discordAlertService.onAdminAlert(NEW_USER);
        discordAlertService.onAdminAlert(BLOCKED);

        ((DefaultDiscordAlertService) discordAlertService).flushBeforeShutdown();

        final var message = ArgumentCaptor.forClass(DiscordMessage.class);
        verify(discordWebhookClient).sendWithoutWaiting(eq(WEBHOOK_URL), message.capture(), eq(Duration.ofSeconds(5)));
        verify(discordWebhookClient, never()).send(anyString(), any(DiscordMessage.class), any(Duration.class));
        assertThat(message.getValue().content().lines()).hasSize(2);
    }

    @Test
    void closingTheApplicationContextSendsThePendingAlerts() {
        final var context = listeningContext();
        context.publishEvent(NEW_USER);
        verifyNoInteractions(discordWebhookClient);

        context.close();

        verify(discordWebhookClient).sendWithoutWaiting(eq(WEBHOOK_URL), any(DiscordMessage.class), eq(Duration.ofSeconds(5)));
    }

    @Test
    void shuttingDownWithNothingBufferedSendsNothing() {
        ((DefaultDiscordAlertService) discordAlertService).flushBeforeShutdown();

        verifyNoInteractions(discordWebhookClient);
    }

    @Test
    void pendingAlertsReachALocalWebhookOnShutdownAndAnUnresponsiveOneHoldsShutdownForAtMostFiveSeconds() throws IOException {
        final var bodies = new CopyOnWriteArrayList<String>();
        final ExecutorService executor = Executors.newCachedThreadPool();
        final var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/webhooks/1/token", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/api/webhooks/2/stuck", exchange -> {
            try {
                Thread.sleep(30_000);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        final var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            appProperties.setDiscordWebhookUrl(baseUrl + "/api/webhooks/1/token");
            final var answering = (DefaultDiscordAlertService) service(new DiscordWebhookClient(JsonMapper.builder().build()));
            answering.onAdminAlert(NEW_USER);
            answering.flushBeforeShutdown();

            appProperties.setDiscordWebhookUrl(baseUrl + "/api/webhooks/2/stuck");
            final var stuck = (DefaultDiscordAlertService) service(new DiscordWebhookClient(JsonMapper.builder().build()));
            stuck.onAdminAlert(NEW_USER);
            final var started = System.nanoTime();
            stuck.flushBeforeShutdown();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }

        assertThat(bodies).hasSize(1);
        assertThat(JsonMapper.builder().build().readTree(bodies.getFirst()).path("content").asString())
                .isEqualTo("New user: account #31 signed up with email, <https://app.hrva.cc/admin>");
    }

    private DiscordAlertService service(final DiscordWebhookClient client) {
        return new DefaultDiscordAlertService(appProperties, new DiscordAlertFormatter(appProperties), client);
    }

    private AnnotationConfigApplicationContext listeningContext() {
        final var context = new AnnotationConfigApplicationContext();
        context.registerBean(TransactionalEventListenerFactory.class);
        context.registerBean(DefaultDiscordAlertService.class, () -> (DefaultDiscordAlertService) discordAlertService);
        context.refresh();
        return context;
    }

    private static final class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(final Object transaction, final TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(final DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(final DefaultTransactionStatus status) {
        }

    }

}
