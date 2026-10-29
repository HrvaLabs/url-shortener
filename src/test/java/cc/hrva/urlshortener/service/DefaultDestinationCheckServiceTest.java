package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.DestinationProber;
import cc.hrva.urlshortener.client.ProbeOutcome;
import cc.hrva.urlshortener.client.ProbeResult;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.DestinationCheckRepository.DueLink;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.impl.DefaultDestinationCheckService;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.notification.NetworkCanary;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultDestinationCheckServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T01:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 10, 27, 1, 0);
    private static final LocalDateTime TONIGHT_STARTED = LocalDateTime.of(2026, 10, 26, 23, 0);
    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 27);
    private static final LocalDateTime DEAD_SINCE = LocalDateTime.of(2026, 10, 20, 1, 3);
    private static final ProbeResult ALIVE = new ProbeResult(ProbeOutcome.ALIVE, "ok", 200);
    private static final ProbeResult NOT_FOUND = new ProbeResult(ProbeOutcome.DEAD, "404", 404);
    private static final ProbeResult TIMEOUT = new ProbeResult(ProbeOutcome.UNKNOWN, "timeout", null);

    @Mock
    private DestinationCheckRepository repository;

    private final MutableClock clock = new MutableClock(NOW);
    private final AppProperties appProperties = new AppProperties();
    private final Map<String, ProbeResult> answers = new ConcurrentHashMap<>();
    private final List<String> probed = new CopyOnWriteArrayList<>();
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private final AtomicBoolean networkUp = new AtomicBoolean(true);
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(DefaultDestinationCheckService.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private DestinationProber prober = (url, budget, beforeEachRequest) -> answer(url, beforeEachRequest);
    private DestinationCheckService service;

    @BeforeEach
    void setUp() {
        logEvents.start();
        serviceLogger.addAppender(logEvents);
        when(repository.record(anyLong(), anyString(), any(), anyInt(), any(), any())).thenReturn(true);
        when(repository.touch(anyLong(), anyString(), any())).thenReturn(true);
        service = newService();
    }

    @AfterEach
    void detachAppender() {
        serviceLogger.detachAppender(logEvents);
    }

    @Test
    void threeDeadNightsMarkDead() {
        final var link = due(7L, "https://example.com/menu", 2, null, 40L);
        answers.put(link.longUrl(), NOT_FOUND);
        batches(List.of(link));

        final var outcome = service.checkAll();

        verify(repository).record(7L, "https://example.com/menu", NOW_UTC, 3, NOW_UTC, "404");
        assertThat(outcome).isEqualTo(new DestinationCheckOutcome(
                List.of(new DeadLink(7L, 40L, "menu", "example.com", NOW_UTC, "404")), List.of(), NIGHT));
    }

    @Test
    void twoDeadNightsDoNotMarkDead() {
        final var link = due(7L, "https://example.com/menu", 1, null, 40L);
        answers.put(link.longUrl(), new ProbeResult(ProbeOutcome.DEAD, "nxdomain", null));
        batches(List.of(link));

        final var outcome = service.checkAll();

        verify(repository).record(7L, "https://example.com/menu", NOW_UTC, 2, null, "nxdomain");
        assertThat(outcome.newlyDead()).isEmpty();
    }

    @Test
    void anAlreadyDeadLinkKeepsItsDeadSinceAndIsNotNewlyDeadAgain() {
        final var link = due(7L, "https://example.com/menu", 5, DEAD_SINCE, 40L);
        answers.put(link.longUrl(), new ProbeResult(ProbeOutcome.DEAD, "410", 410));
        batches(List.of(link));

        final var outcome = service.checkAll();

        verify(repository).record(7L, "https://example.com/menu", NOW_UTC, 6, DEAD_SINCE, "410");
        assertThat(outcome.newlyDead()).isEmpty();
        assertThat(outcome.recovered()).isEmpty();
    }

    @Test
    void aliveResetsAndRecovers() {
        final var dead = due(7L, "https://example.com/menu", 4, DEAD_SINCE, 40L);
        final var failing = due(8L, "https://example.org/list", 2, null, null);
        answers.put(dead.longUrl(), ALIVE);
        answers.put(failing.longUrl(), ALIVE);
        batches(List.of(dead, failing));

        final var outcome = service.checkAll();

        verify(repository).record(7L, "https://example.com/menu", NOW_UTC, 0, null, null);
        verify(repository).record(8L, "https://example.org/list", NOW_UTC, 0, null, null);
        assertThat(outcome.recovered()).containsExactly(new DeadLink(7L, 40L, "menu", "example.com", DEAD_SINCE, null));
        assertThat(outcome.newlyDead()).isEmpty();
    }

    @Test
    void unknownNightsKeepTheStreak() {
        final var link = due(7L, "https://example.com/menu", 2, null, 40L);
        answers.put(link.longUrl(), TIMEOUT);
        batches(List.of(link));

        final var outcome = service.checkAll();

        verify(repository).touch(7L, "https://example.com/menu", NOW_UTC);
        verify(repository, never()).record(anyLong(), anyString(), any(), anyInt(), any(), any());
        assertThat(outcome).isEqualTo(new DestinationCheckOutcome(List.of(), List.of(), NIGHT));
    }

    @Test
    void forbiddenAndServerErrorsAreUnknownNightsToo() {
        final var walled = due(7L, "https://example.com/menu", 2, null, 40L);
        final var down = due(8L, "https://example.org/list", 2, DEAD_SINCE, 40L);
        answers.put(walled.longUrl(), new ProbeResult(ProbeOutcome.UNKNOWN, "http-403", 403));
        answers.put(down.longUrl(), new ProbeResult(ProbeOutcome.UNKNOWN, "http-503", 503));
        batches(List.of(walled, down));

        final var outcome = service.checkAll();

        verify(repository).touch(7L, "https://example.com/menu", NOW_UTC);
        verify(repository).touch(8L, "https://example.org/list", NOW_UTC);
        verify(repository, never()).record(anyLong(), anyString(), any(), anyInt(), any(), any());
        assertThat(outcome.recovered()).isEmpty();
    }

    @Test
    void aLinkIsCheckedOnceANight() {
        batches(List.of());

        service.checkAll();

        verify(repository).findDue(eq(TONIGHT_STARTED), eq(200), any());
    }

    @Test
    void tonightStartsAtMidnightInTheAnalyticsZoneInSummerToo() {
        clock.advance(Duration.between(NOW, Instant.parse("2026-07-01T00:30:00Z")));
        batches(List.of());

        final var outcome = service.checkAll();

        verify(repository).findDue(eq(LocalDateTime.of(2026, 6, 30, 22, 0)), eq(200), any());
        assertThat(outcome.night()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void batchesRunUntilNoneAreLeft() {
        final var first = due(7L, "https://example.com/a", 0, null, 40L);
        final var second = due(8L, "https://example.com/b", 0, null, 40L);
        answers.put(first.longUrl(), ALIVE);
        answers.put(second.longUrl(), ALIVE);
        batches(List.of(first), List.of(second));

        service.checkAll();

        verify(repository, times(3)).findDue(eq(TONIGHT_STARTED), eq(200), any());
        assertThat(probed).containsExactly("https://example.com/a", "https://example.com/b");
    }

    @Test
    void linksThatProducedNoWriteAreLeftOutOfTheNextBatch() {
        final var edited = due(7L, "https://example.com/edited", 0, null, 40L);
        final var next = due(8L, "https://example.com/next", 0, null, 40L);
        answers.put(edited.longUrl(), ALIVE);
        answers.put(next.longUrl(), ALIVE);
        when(repository.record(eq(7L), anyString(), any(), anyInt(), any(), any())).thenReturn(false);
        final var exclusions = new java.util.ArrayList<java.util.Set<Long>>();
        when(repository.findDue(any(), anyInt(), any())).thenAnswer(invocation -> {
            final java.util.Collection<Long> excluded = invocation.getArgument(2);
            exclusions.add(java.util.Set.copyOf(excluded));
            if (!excluded.contains(7L)) {
                return List.of(edited);
            }
            return excluded.contains(8L) ? List.of() : List.of(next);
        });

        final var outcome = service.checkAll();

        assertThat(probed).containsExactly("https://example.com/edited", "https://example.com/next");
        assertThat(exclusions).containsExactly(java.util.Set.of(), java.util.Set.of(7L), java.util.Set.of(7L, 8L));
        assertThat(messages()).anySatisfy(line -> assertThat(line).startsWith("Destination check finished").endsWith("stoppedEarly=false"));
        assertThat(outcome.recovered()).isEmpty();
    }

    @Test
    void aBatchOfLinksAlreadyTriedEndsTheRun() {
        final var link = due(7L, "https://example.com/menu", 0, null, 40L);
        answers.put(link.longUrl(), ALIVE);
        when(repository.record(anyLong(), anyString(), any(), anyInt(), any(), any())).thenReturn(false);
        when(repository.findDue(any(), anyInt(), any())).thenReturn(List.of(link));

        service.checkAll();

        verify(repository, times(2)).findDue(eq(TONIGHT_STARTED), eq(200), any());
        assertThat(probed).containsExactly("https://example.com/menu");
    }

    @Test
    void aResultForAnEditedDestinationIsDropped() {
        final var dying = due(7L, "https://example.com/menu", 2, null, 40L);
        final var recovering = due(8L, "https://example.org/list", 3, DEAD_SINCE, 40L);
        answers.put(dying.longUrl(), NOT_FOUND);
        answers.put(recovering.longUrl(), ALIVE);
        when(repository.record(anyLong(), anyString(), any(), anyInt(), any(), any())).thenReturn(false);
        batches(List.of(dying, recovering));

        final var outcome = service.checkAll();

        assertThat(outcome.newlyDead()).isEmpty();
        assertThat(outcome.recovered()).isEmpty();
    }

    @Test
    void canaryFailureStopsTheRunAndRecordsNothing() {
        networkUp.set(false);
        final var link = due(7L, "https://example.com/menu", 2, null, 40L);
        final var other = due(8L, "https://example.org/list", 2, null, 40L);
        answers.put(link.longUrl(), new ProbeResult(ProbeOutcome.DEAD, "nxdomain", null));
        answers.put(other.longUrl(), new ProbeResult(ProbeOutcome.DEAD, "refused", null));
        batches(List.of(link, other), List.of(due(9L, "https://example.net/", 0, null, 40L)));

        final var outcome = service.checkAll();

        verify(repository, never()).record(anyLong(), anyString(), any(), anyInt(), any(), any());
        verify(repository, times(1)).findDue(any(), anyInt(), any());
        assertThat(outcome.newlyDead()).isEmpty();
        assertThat(messages()).contains("Destination check stopped: the network looks down")
                .anyMatch(message -> message.startsWith("Destination check finished") && message.endsWith("stoppedEarly=true"));
    }

    @Test
    void canaryIsAskedOnlyForResultsThatAServerOutageCouldCause() {
        networkUp.set(false);
        final var gone = due(7L, "https://example.com/menu", 2, null, 40L);
        final var tls = due(8L, "https://example.org/list", 0, null, 40L);
        answers.put(gone.longUrl(), NOT_FOUND);
        answers.put(tls.longUrl(), new ProbeResult(ProbeOutcome.DEAD, "tls", null));
        batches(List.of(gone));

        service.checkAll();

        verify(repository).record(7L, "https://example.com/menu", NOW_UTC, 3, NOW_UTC, "404");
        assertThat(messages()).doesNotContain("Destination check stopped: the network looks down");
    }

    @Test
    void aHealthyCanaryLetsDefiniteFailuresCount() {
        final var link = due(7L, "https://example.com/menu", 0, null, 40L);
        answers.put(link.longUrl(), new ProbeResult(ProbeOutcome.DEAD, "tls", null));
        batches(List.of(link));

        service.checkAll();

        verify(repository).record(7L, "https://example.com/menu", NOW_UTC, 1, null, "tls");
    }

    @Test
    void budgetStopsTheRun() {
        appProperties.getDestinationCheck().setBudget(Duration.ofMinutes(30));
        final var first = due(7L, "https://example.com/a", 0, null, 40L);
        answers.put(first.longUrl(), ALIVE);
        prober = (url, budget, beforeEachRequest) -> {
            clock.advance(Duration.ofMinutes(31));
            return answer(url, beforeEachRequest);
        };
        service = newService();
        batches(List.of(first), List.of(due(8L, "https://example.com/b", 0, null, 40L)));

        service.checkAll();

        verify(repository, times(1)).findDue(any(), anyInt(), any());
        assertThat(messages()).anyMatch(message -> message.endsWith("stoppedEarly=true"));
    }

    @Test
    void linksLeftInABatchAfterTheBudgetAreNotProbed() {
        appProperties.getDestinationCheck().setBudget(Duration.ofMinutes(30));
        prober = (url, budget, beforeEachRequest) -> {
            clock.advance(Duration.ofMinutes(31));
            return answer(url, beforeEachRequest);
        };
        service = newService();
        final var links = List.of(due(7L, "https://example.com/a", 0, null, 40L), due(8L, "https://example.com/b", 0, null, 40L),
                due(9L, "https://example.com/c", 0, null, 40L), due(10L, "https://example.com/d", 0, null, 40L),
                due(11L, "https://example.com/e", 0, null, 40L), due(12L, "https://example.com/f", 0, null, 40L));
        links.forEach(link -> answers.put(link.longUrl(), ALIVE));
        batches(links);

        service.checkAll();

        assertThat(probed.size()).isLessThan(links.size());
    }

    @Test
    void offWhenDisabled() {
        appProperties.getDestinationCheck().setEnabled(false);

        final var outcome = service.checkAll();

        verify(repository, never()).findDue(any(), anyInt(), any());
        assertThat(outcome).isEqualTo(new DestinationCheckOutcome(List.of(), List.of(), NIGHT));
    }

    @Test
    void anOverlappingRunIsSkipped() throws Exception {
        final var probing = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var link = due(7L, "https://example.com/menu", 0, null, 40L);
        prober = (url, budget, beforeEachRequest) -> {
            probing.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return ALIVE;
        };
        service = newService();
        batches(List.of(link));
        final var executor = Executors.newSingleThreadExecutor();
        try {
            final var first = executor.submit(service::checkAll);
            assertThat(probing.await(5, TimeUnit.SECONDS)).isTrue();

            final var second = service.checkAll();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);

            assertThat(second).isEqualTo(new DestinationCheckOutcome(List.of(), List.of(), NIGHT));
            verify(repository, times(2)).findDue(any(), anyInt(), any());
            assertThat(messages()).containsOnlyOnce("Destination check skipped: the previous run is still going");
        } finally {
            executor.shutdownNow();
        }

        batches(List.of());
        service.checkAll();
        verify(repository, times(3)).findDue(any(), anyInt(), any());
    }

    @Test
    void probesShareAPacerPerHostWithAFifteenSecondBudget() {
        final var budgets = new CopyOnWriteArrayList<Duration>();
        prober = (url, budget, beforeEachRequest) -> {
            budgets.add(budget);
            return answer(url, beforeEachRequest);
        };
        service = newService();
        final var first = due(7L, "https://example.com/a", 0, null, 40L);
        final var second = due(8L, "https://EXAMPLE.com/b", 0, null, 40L);
        final var other = due(9L, "https://example.org/c", 0, null, 40L);
        List.of(first, second, other).forEach(link -> answers.put(link.longUrl(), ALIVE));
        batches(List.of(first, second, other));

        service.checkAll();

        assertThat(budgets).containsOnly(Duration.ofSeconds(15)).hasSize(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(5));
    }

    @Test
    void probesRunFourAtATimeOnNamedThreads() throws Exception {
        final var threads = new ConcurrentHashMap<String, Boolean>();
        final var started = new CountDownLatch(4);
        prober = (url, budget, beforeEachRequest) -> {
            threads.put(Thread.currentThread().getName(), true);
            started.countDown();
            try {
                started.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return ALIVE;
        };
        service = newService();
        final var links = List.of(due(7L, "https://a.example/", 0, null, 40L), due(8L, "https://b.example/", 0, null, 40L),
                due(9L, "https://c.example/", 0, null, 40L), due(10L, "https://d.example/", 0, null, 40L),
                due(11L, "https://e.example/", 0, null, 40L));
        batches(links);

        service.checkAll();

        assertThat(started.getCount()).isZero();
        assertThat(threads.keySet()).hasSize(4).allMatch(name -> name.matches("destination-check-\\d+"));
    }

    @Test
    void aFailedWriteIsLoggedByIdAndTheRunGoesOn() {
        final var broken = due(7L, "https://example.com/secret-path", 0, null, 40L);
        final var fine = due(8L, "https://example.org/list", 0, null, 40L);
        answers.put(broken.longUrl(), ALIVE);
        answers.put(fine.longUrl(), ALIVE);
        when(repository.record(eq(7L), anyString(), any(), anyInt(), any(), any()))
                .thenThrow(new QueryTimeoutException("https://example.com/secret-path"));
        batches(List.of(broken, fine));

        service.checkAll();

        verify(repository).record(8L, "https://example.org/list", NOW_UTC, 0, null, null);
        assertThat(messages()).anyMatch(message -> message.contains("url id=7"));
    }

    @Test
    void theFinishLineCountsEveryResult() {
        final var alive = due(7L, "https://example.com/a", 0, null, 40L);
        final var recovered = due(8L, "https://example.com/b", 3, DEAD_SINCE, 40L);
        final var dying = due(9L, "https://example.org/c", 2, null, 40L);
        final var unknown = due(10L, "https://example.net/d", 0, null, null);
        answers.put(alive.longUrl(), ALIVE);
        answers.put(recovered.longUrl(), ALIVE);
        answers.put(dying.longUrl(), NOT_FOUND);
        answers.put(unknown.longUrl(), TIMEOUT);
        batches(List.of(alive, recovered, dying, unknown));

        service.checkAll();

        assertThat(messages()).contains(
                "Destination check finished checked=4 alive=2 dead=1 unknown=1 newlyDead=1 recovered=1 stoppedEarly=false");
    }

    @Test
    void noUrlInAnyLogLine() {
        networkUp.set(false);
        final var dying = due(7L, "https://secret-host.example/private-path?token=abc", 2, null, 40L);
        final var broken = due(8L, "https://other-secret.example/x", 0, null, 40L);
        answers.put(dying.longUrl(), new ProbeResult(ProbeOutcome.DEAD, "nxdomain", null));
        answers.put(broken.longUrl(), ALIVE);
        when(repository.record(eq(8L), anyString(), any(), anyInt(), any(), any()))
                .thenThrow(new QueryTimeoutException("https://other-secret.example/x"));
        batches(List.of(dying, broken));

        service.checkAll();
        appProperties.getDestinationCheck().setEnabled(false);
        service.checkAll();

        assertThat(logEvents.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("secret").doesNotContain("example").doesNotContain("token");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void runNightlyRunsTheCheckAsyncOutsideAnyTransaction() throws NoSuchMethodException {
        final var runNightly = DefaultDestinationCheckService.class.getMethod("runNightly");
        final var checkAll = DefaultDestinationCheckService.class.getMethod("checkAll");

        assertThat(runNightly.getAnnotation(Async.class)).isNotNull();
        assertThat(runNightly.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(checkAll.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.NOT_SUPPORTED);

        batches(List.of());
        service.runNightly();
        verify(repository).findDue(eq(TONIGHT_STARTED), eq(200), any());
    }

    private DestinationCheckService newService() {
        final var canary = new NetworkCanary(host -> {
            if (!networkUp.get()) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 215, 14})};
        }, clock);
        return new DefaultDestinationCheckService(repository, prober, canary, new AnalyticsDays(clock, appProperties), appProperties,
                sleeps::add, clock);
    }

    private ProbeResult answer(final String url, final Consumer<String> beforeEachRequest) {
        probed.add(url);
        beforeEachRequest.accept(URI.create(url).getHost());
        return answers.getOrDefault(url, TIMEOUT);
    }

    @SafeVarargs
    private void batches(final List<DueLink>... batches) {
        var stubbing = when(repository.findDue(any(), anyInt(), any()));
        for (final var batch : batches) {
            stubbing = stubbing.thenReturn(batch);
        }
        stubbing.thenReturn(List.of());
    }

    private List<String> messages() {
        return logEvents.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static DueLink due(final long id, final String longUrl, final int failStreak, final LocalDateTime deadSince,
            final Long ownerId) {
        final var path = URI.create(longUrl).getPath();
        return new DueLink(id, longUrl, failStreak, deadSince, ownerId, path.length() <= 1 ? "link" + id : path.substring(1));
    }

}
