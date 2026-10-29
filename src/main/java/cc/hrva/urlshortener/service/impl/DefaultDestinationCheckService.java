package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.DestinationProber;
import cc.hrva.urlshortener.client.ProbeResult;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.DestinationCheckRepository.DueLink;
import cc.hrva.urlshortener.service.DeadLinkNotificationService;
import cc.hrva.urlshortener.service.DestinationCheckService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import cc.hrva.urlshortener.service.notification.HostPacer;
import cc.hrva.urlshortener.service.notification.NetworkCanary;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultDestinationCheckService implements DestinationCheckService {

    private static final int BATCH_SIZE = 200;
    private static final int PARALLEL_PROBES = 4;
    private static final int DEAD_AFTER_NIGHTS = 3;
    private static final Duration PROBE_BUDGET = Duration.ofSeconds(15);
    private static final Duration HOST_INTERVAL = Duration.ofSeconds(5);
    private static final Set<String> OUTAGE_CODES = Set.of("nxdomain", "refused", "tls");

    private final DestinationCheckRepository destinationCheckRepository;
    private final DestinationProber destinationProber;
    private final NetworkCanary networkCanary;
    private final AnalyticsDays analyticsDays;
    private final AppProperties appProperties;
    private final HostPacer.Sleeper hostPacerSleeper;
    private final DeadLinkNotificationService deadLinkNotificationService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DestinationCheckOutcome checkAll() {
        final var night = analyticsDays.today();
        if (!appProperties.getDestinationCheck().isEnabled()) {
            log.info("Destination check is turned off");
            return DestinationCheckOutcome.nothing(night);
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Destination check skipped: the previous run is still going");
            return DestinationCheckOutcome.nothing(night);
        }
        try {
            return new Run(night).check();
        } finally {
            running.set(false);
        }
    }

    @Async
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runNightly() {
        deadLinkNotificationService.notify(checkAll());
    }

    private enum Verdict {
        ALIVE,
        DEAD,
        UNKNOWN,
        NOT_CHECKED
    }

    private record LinkResult(Verdict verdict, DeadLink newlyDead, DeadLink recovered, boolean written) {

        private static final LinkResult NOT_CHECKED = new LinkResult(Verdict.NOT_CHECKED, null, null, false);

    }

    private final class Run {

        private final LocalDate night;
        private final LocalDateTime nightStart;
        private final Instant stopAt;
        private final HostPacer pacer = new HostPacer(clock, hostPacerSleeper, HOST_INTERVAL);
        private final AtomicBoolean networkDown = new AtomicBoolean();
        private final AtomicBoolean outOfTime = new AtomicBoolean();
        private final List<DeadLink> newlyDead = new ArrayList<>();
        private final List<DeadLink> recovered = new ArrayList<>();
        private int alive;
        private int dead;
        private int unknown;

        private Run(final LocalDate night) {
            this.night = night;
            this.nightStart = LocalDateTime.ofInstant(night.atStartOfDay(analyticsDays.zone()).toInstant(), clock.getZone());
            this.stopAt = clock.instant().plus(appProperties.getDestinationCheck().getBudget());
        }

        private DestinationCheckOutcome check() {
            final var executor = Executors.newFixedThreadPool(PARALLEL_PROBES, Thread.ofPlatform().name("destination-check-", 1).factory());
            final var attempted = new HashSet<Long>();
            final var unwritten = new HashSet<Long>();
            try {
                while (!stopped() && hasTimeLeft()) {
                    final var due = destinationCheckRepository.findDue(nightStart, BATCH_SIZE, Set.copyOf(unwritten)).stream()
                            .filter(link -> attempted.add(link.id()))
                            .toList();
                    if (due.isEmpty()) {
                        break;
                    }
                    final var results = due.stream().map(link -> executor.submit(() -> check(link))).toList();
                    for (var index = 0; index < due.size(); index++) {
                        final var result = awaited(results.get(index));
                        tally(result);
                        if (!result.written()) {
                            unwritten.add(due.get(index).id());
                        }
                    }
                }
            } finally {
                executor.shutdownNow();
            }
            log.info("Destination check finished checked={} alive={} dead={} unknown={} newlyDead={} recovered={} stoppedEarly={}",
                    alive + dead + unknown, alive, dead, unknown, newlyDead.size(), recovered.size(), stopped());
            return new DestinationCheckOutcome(List.copyOf(newlyDead), List.copyOf(recovered), night);
        }

        private boolean stopped() {
            return networkDown.get() || outOfTime.get();
        }

        private boolean hasTimeLeft() {
            if (clock.instant().isBefore(stopAt)) {
                return true;
            }
            outOfTime.set(true);
            return false;
        }

        private LinkResult check(final DueLink link) {
            if (stopped() || !hasTimeLeft()) {
                return LinkResult.NOT_CHECKED;
            }
            try {
                return recorded(link, destinationProber.probe(link.longUrl(), PROBE_BUDGET, pacer::awaitTurn));
            } catch (final RuntimeException exception) {
                log.warn("Destination check failed url id={} exception={}", link.id(), exception.getClass().getName());
                return LinkResult.NOT_CHECKED;
            }
        }

        private LinkResult recorded(final DueLink link, final ProbeResult result) {
            final var now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            return switch (result.outcome()) {
                case ALIVE -> {
                    final var written = destinationCheckRepository.record(link.id(), link.longUrl(), now, 0, null, null);
                    final var back = written && link.deadSince() != null ? deadLink(link, link.deadSince(), null) : null;
                    yield new LinkResult(Verdict.ALIVE, null, back, written);
                }
                case DEAD -> {
                    if (OUTAGE_CODES.contains(result.code()) && !networkCanary.healthy()) {
                        if (networkDown.compareAndSet(false, true)) {
                            log.warn("Destination check stopped: the network looks down");
                        }
                        yield LinkResult.NOT_CHECKED;
                    }
                    final var streak = link.failStreak() + 1;
                    final var diesTonight = link.deadSince() == null && streak >= DEAD_AFTER_NIGHTS;
                    final var deadSince = diesTonight ? now : link.deadSince();
                    final var written = destinationCheckRepository.record(link.id(), link.longUrl(), now, streak, deadSince, result.code());
                    yield new LinkResult(Verdict.DEAD, written && diesTonight ? deadLink(link, now, result.code()) : null, null, written);
                }
                case UNKNOWN -> {
                    final var written = destinationCheckRepository.touch(link.id(), link.longUrl(), now);
                    yield new LinkResult(Verdict.UNKNOWN, null, null, written);
                }
            };
        }

        private LinkResult awaited(final Future<LinkResult> result) {
            try {
                return result.get();
            } catch (final ExecutionException exception) {
                log.warn("Destination check failed exception={}", exception.getCause().getClass().getName());
                return LinkResult.NOT_CHECKED;
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                outOfTime.set(true);
                return LinkResult.NOT_CHECKED;
            }
        }

        private void tally(final LinkResult result) {
            switch (result.verdict()) {
                case ALIVE -> alive++;
                case DEAD -> dead++;
                case UNKNOWN -> unknown++;
                case NOT_CHECKED -> {
                    return;
                }
            }
            if (result.newlyDead() != null) {
                newlyDead.add(result.newlyDead());
            }
            if (result.recovered() != null) {
                recovered.add(result.recovered());
            }
        }

        private static DeadLink deadLink(final DueLink link, final LocalDateTime deadSince, final String lastStatus) {
            return new DeadLink(link.id(), link.ownerId(), link.shortUrl(), DomainNames.hostOf(link.longUrl()).orElse(null),
                    deadSince, lastStatus);
        }

    }

}
