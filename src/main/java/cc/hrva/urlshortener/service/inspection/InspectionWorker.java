package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.client.BrowserRunClient;
import cc.hrva.urlshortener.client.PageSnapshot;
import cc.hrva.urlshortener.client.VirusTotalClient;
import cc.hrva.urlshortener.client.VirusTotalReport;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.LinkInspection;
import cc.hrva.urlshortener.model.LinkScreenshot;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.InspectionRequester;
import cc.hrva.urlshortener.model.enums.InspectionState;
import cc.hrva.urlshortener.repository.LinkInspectionRepository;
import cc.hrva.urlshortener.repository.LinkScreenshotRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionWorker {

    private static final String DAILY_LIMIT = "Today's screenshot time is used up. It's taken after midnight UTC.";
    private static final String BLOCKED_BEFORE = "The link was blocked before its screenshot was taken.";
    private static final String TOKEN_REFUSED = "Cloudflare refused the API token.";
    private static final String TAKING_FAILED = "Taking the screenshot failed. Take it again to retry.";
    private static final Duration PAUSE_AFTER_EXCEPTION = Duration.ofMinutes(1);
    private static final Duration PAUSE_AFTER_REFUSED_TOKEN = Duration.ofHours(1);
    private static final String VIRUSTOTAL_DAILY_LIMIT = "Today's VirusTotal lookups are used up. They continue after midnight UTC.";
    private static final String VIRUSTOTAL_LOOKUP_FAILED = "Looking up the link on VirusTotal failed. Send it to VirusTotal to try again.";
    private static final Duration WAIT_FOR_THE_REDIRECT_CHECK = Duration.ofMinutes(2);
    private static final Duration PAUSE_AFTER_VIRUSTOTAL_RATE_LIMIT = Duration.ofMinutes(1);
    private static final Duration PAUSE_AFTER_VIRUSTOTAL_FAILURE = Duration.ofMinutes(1);
    private static final Duration PAUSE_AFTER_REFUSED_KEY = Duration.ofHours(1);

    private final LinkInspectionRepository linkInspectionRepository;
    private final LinkScreenshotRepository linkScreenshotRepository;
    private final UrlRepository urlRepository;
    private final BrowserRunClient browserRunClient;
    private final VirusTotalClient virusTotalClient;
    private final RateLimiter rateLimiter;
    private final SettingsService settingsService;
    private final AppProperties appProperties;
    private final TransactionOperations transactionOperations;
    private final Clock clock;
    private final AtomicReference<Instant> pausedUntil = new AtomicReference<>(Instant.MIN);
    private final AtomicReference<Attempt> failedBefore = new AtomicReference<>();
    private final AtomicReference<Instant> virusTotalPausedUntil = new AtomicReference<>(Instant.MIN);
    private final AtomicReference<Attempt> virusTotalFailedBefore = new AtomicReference<>();

    @Scheduled(fixedDelayString = "${app.inspection.interval:15s}")
    public void takeNextScreenshot() {
        final Optional<Request> next;
        try {
            if (clock.instant().isBefore(pausedUntil.get()) || !settingsService.isOnAndAvailable(SettingKey.LINK_SCREENSHOTS)) {
                return;
            }
            next = transactionOperations.execute(status -> nextRequest());
        } catch (final RuntimeException exception) {
            pauseAfter(exception, null);
            return;
        }
        if (next == null) {
            return;
        }
        forgetFailureUnlessHead(failedBefore, next.map(Request::attempt));
        next.ifPresent(this::takeOrCountTheFailure);
    }

    @Scheduled(fixedDelayString = "${app.inspection.interval:15s}")
    public void lookUpNextOnVirusTotal() {
        final Optional<Lookup> next;
        try {
            if (clock.instant().isBefore(virusTotalPausedUntil.get()) || !settingsService.isOnAndAvailable(SettingKey.VIRUSTOTAL_LOOKUPS)) {
                return;
            }
            next = transactionOperations.execute(status -> nextLookup());
        } catch (final RuntimeException exception) {
            pauseLookupsAfter(exception, null);
            return;
        }
        if (next == null) {
            return;
        }
        forgetFailureUnlessHead(virusTotalFailedBefore, next.map(Lookup::attempt));
        next.ifPresent(this::lookUpOrCountTheFailure);
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void expireOldScreenshots() {
        try {
            final var cutoff = LocalDateTime.now(clock).minusDays(appProperties.getInspection().getKeepScreenshotDays());
            final var expired = transactionOperations.execute(status -> {
                linkScreenshotRepository.deleteTakenBefore(cutoff);
                return linkInspectionRepository.expireTakenBefore(cutoff);
            });
            log.info("Scheduled cleanup: old screenshots expired={}", expired);
        } catch (final RuntimeException exception) {
            log.warn("Scheduled cleanup of old screenshots failed exception={}", exception.getClass().getName());
        }
    }

    void resume() {
        pausedUntil.set(Instant.MIN);
        failedBefore.set(null);
        virusTotalPausedUntil.set(Instant.MIN);
        virusTotalFailedBefore.set(null);
    }

    private Optional<Lookup> nextLookup() {
        final var now = LocalDateTime.now(clock);
        return linkInspectionRepository.findDueOnVirusTotal(now, now.minus(WAIT_FOR_THE_REDIRECT_CHECK), Limit.of(1)).stream()
                .findFirst()
                .flatMap(inspection -> urlRepository.findById(inspection.getUrlId()).map(url -> Lookup.of(inspection, url)));
    }

    private void lookUpOrCountTheFailure(final Lookup lookup) {
        try {
            lookUp(lookup);
        } catch (final RuntimeException exception) {
            pauseLookupsAfter(exception, lookup.urlId());
            if (failedTwice(virusTotalFailedBefore, lookup.attempt())) {
                try {
                    recordLookup(lookup, InspectionState.FAILED, VIRUSTOTAL_LOOKUP_FAILED);
                } catch (final RuntimeException again) {
                    log.warn("VirusTotal failure could not be recorded urlId={} exception={}", lookup.urlId(), again.getClass().getName());
                }
            }
        }
    }

    private void lookUp(final Lookup lookup) {
        final var candidates = VirusTotalCandidates.of(lookup.longUrl(), lookup.finalUrl());
        if (lookup.nextCandidate() >= candidates.size()) {
            recordNotFound(lookup);
            return;
        }
        if (!countedWithinTheDailyLimit()) {
            reachedTheDailyLimit(lookup);
            return;
        }
        final var candidate = candidates.get(lookup.nextCandidate());
        final var report = virusTotalClient.lookup(candidate);
        if (report.outcome() == VirusTotalReport.Outcome.NOT_FOUND && lookup.nextCandidate() + 1 < candidates.size()) {
            virusTotalFailedBefore.set(null);
            recordNextCandidate(lookup, lookup.nextCandidate() + 1);
            return;
        }
        recordReport(lookup, report, candidate);
    }

    private void recordNextCandidate(final Lookup lookup, final int nextCandidate) {
        final var updated = transactionOperations.execute(status -> linkInspectionRepository.recordVirusTotalNextCandidate(lookup.urlId(),
                lookup.requestedAt(), nextCandidate));
        logRecorded(lookup, updated, InspectionState.PENDING);
    }

    private boolean countedWithinTheDailyLimit() {
        return rateLimiter.increment(VirusTotalQuota.keyOfToday(clock), VirusTotalQuota.KEPT_FOR)
                <= appProperties.getInspection().getVirusTotalDailyLimit();
    }

    private void reachedTheDailyLimit(final Lookup lookup) {
        pause(virusTotalPausedUntil, nextMidnightUtc());
        recordLookup(lookup, InspectionState.PENDING, VIRUSTOTAL_DAILY_LIMIT);
        log.info("VirusTotal lookups paused until midnight UTC, today's lookups are used up urlId={}", lookup.urlId());
    }

    private void recordReport(final Lookup lookup, final VirusTotalReport report, final String checkedUrl) {
        switch (report.outcome()) {
            case FOUND -> {
                virusTotalFailedBefore.set(null);
                recordFound(lookup, report, checkedUrl);
            }
            case NOT_FOUND -> {
                virusTotalFailedBefore.set(null);
                recordNotFound(lookup);
            }
            case RATE_LIMITED -> {
                virusTotalFailedBefore.set(null);
                pause(virusTotalPausedUntil, clock.instant().plus(PAUSE_AFTER_VIRUSTOTAL_RATE_LIMIT));
                log.info("VirusTotal lookups paused for a minute, VirusTotal's rate limit was hit urlId={}", lookup.urlId());
            }
            case FAILED -> recordFailure(lookup, report);
            case NOT_CONFIGURED -> {
                virusTotalFailedBefore.set(null);
                log.debug("VirusTotal lookup skipped, the API key is not set urlId={}", lookup.urlId());
            }
            case SUBMITTED -> throw new IllegalStateException("VirusTotal answered a lookup with " + report.outcome());
        }
    }

    private void recordFailure(final Lookup lookup, final VirusTotalReport report) {
        if (report.keyRefused()) {
            virusTotalFailedBefore.set(null);
            pause(virusTotalPausedUntil, clock.instant().plus(PAUSE_AFTER_REFUSED_KEY));
            recordLookup(lookup, InspectionState.PENDING, report.failure());
            log.warn("VirusTotal lookups paused for an hour, VirusTotal refused the API key urlId={}", lookup.urlId());
            return;
        }
        pause(virusTotalPausedUntil, clock.instant().plus(PAUSE_AFTER_VIRUSTOTAL_FAILURE));
        final var state = failedTwice(virusTotalFailedBefore, lookup.attempt()) ? InspectionState.FAILED : InspectionState.PENDING;
        recordLookup(lookup, state, report.failure());
        log.info("VirusTotal lookup failed, paused for a minute urlId={} state={}", lookup.urlId(), state);
    }

    private void recordFound(final Lookup lookup, final VirusTotalReport report, final String checkedUrl) {
        final var checkedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        final var updated = transactionOperations.execute(status -> linkInspectionRepository.recordVirusTotalFound(lookup.urlId(), lookup.requestedAt(),
                checkedAt, checkedUrl, report.reportId(), report.malicious(), report.suspicious(), report.harmless(), report.undetected(),
                report.analysedAt()));
        logRecorded(lookup, updated, InspectionState.DONE);
    }

    private void recordNotFound(final Lookup lookup) {
        final var checkedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        final var updated = transactionOperations.execute(status -> linkInspectionRepository.recordVirusTotalNotFound(lookup.urlId(),
                lookup.requestedAt(), checkedAt, lookup.longUrl()));
        logRecorded(lookup, updated, InspectionState.NOT_FOUND);
    }

    private void recordLookup(final Lookup lookup, final InspectionState state, final String failure) {
        final var updated = transactionOperations.execute(status -> linkInspectionRepository.recordVirusTotalOutcome(lookup.urlId(),
                lookup.requestedAt(), state, failure));
        logRecorded(lookup, updated, state);
    }

    private static void logRecorded(final Lookup lookup, final Integer updated, final InspectionState state) {
        if (updated == null || updated == 0) {
            log.info("VirusTotal outcome dropped, the link changed or was asked again meanwhile urlId={}", lookup.urlId());
        } else if (state != InspectionState.PENDING) {
            log.info("VirusTotal lookup recorded urlId={} state={}", lookup.urlId(), state);
        }
    }

    private void pauseLookupsAfter(final RuntimeException exception, final Long urlId) {
        pause(virusTotalPausedUntil, clock.instant().plus(PAUSE_AFTER_EXCEPTION));
        log.warn("VirusTotal run failed, paused for a minute urlId={} exception={}", urlId, exception.getClass().getName());
    }

    private static boolean failedTwice(final AtomicReference<Attempt> failure, final Attempt attempt) {
        if (!attempt.equals(failure.getAndSet(attempt))) {
            return false;
        }
        failure.set(null);
        return true;
    }

    private void takeOrCountTheFailure(final Request request) {
        try {
            take(request);
            failedBefore.set(null);
        } catch (final RuntimeException exception) {
            pauseAfter(exception, request.urlId());
            failTheSecondTime(request);
        }
    }

    private void pauseAfter(final RuntimeException exception, final Long urlId) {
        pause(pausedUntil, clock.instant().plus(PAUSE_AFTER_EXCEPTION));
        log.warn("Screenshot run failed, paused for a minute urlId={} exception={}", urlId, exception.getClass().getName());
    }

    private void failTheSecondTime(final Request request) {
        if (!failedTwice(failedBefore, request.attempt())) {
            return;
        }
        try {
            record(request, InspectionState.FAILED, TAKING_FAILED);
        } catch (final RuntimeException exception) {
            log.warn("Screenshot failure could not be recorded urlId={} exception={}", request.urlId(), exception.getClass().getName());
        }
    }

    private Optional<Request> nextRequest() {
        return linkInspectionRepository.findFirstByScreenshotStateOrderByScreenshotRequestedAtAscUrlIdAsc(InspectionState.PENDING)
                .flatMap(inspection -> urlRepository.findById(inspection.getUrlId()).map(url -> Request.of(inspection, url)));
    }

    private void take(final Request request) {
        if (request.requestedBy() != InspectionRequester.ADMIN && request.blocked()) {
            record(request, InspectionState.FAILED, BLOCKED_BEFORE);
            log.info("Screenshot skipped, the link was blocked before it was taken urlId={}", request.urlId());
            return;
        }
        final var snapshot = browserRunClient.snapshot(request.longUrl());
        switch (snapshot.outcome()) {
            case TAKEN -> store(request, snapshot);
            case FAILED -> record(request, InspectionState.FAILED, snapshot.failure());
            case DAILY_LIMIT -> {
                pause(pausedUntil, nextMidnightUtc());
                record(request, InspectionState.PENDING, DAILY_LIMIT);
                log.info("Screenshots paused until midnight UTC, today's browser time is used up urlId={}", request.urlId());
            }
            case RATE_LIMITED -> {
                pause(pausedUntil, clock.instant().plus(snapshot.retryAfter()));
                log.info("Screenshots paused for Cloudflare's rate limit seconds={} urlId={}", snapshot.retryAfter().toSeconds(), request.urlId());
            }
            case TOKEN_REFUSED -> {
                pause(pausedUntil, clock.instant().plus(PAUSE_AFTER_REFUSED_TOKEN));
                record(request, InspectionState.PENDING, TOKEN_REFUSED);
                log.warn("Screenshots paused for an hour, Cloudflare refused the API token urlId={}", request.urlId());
            }
            case NOT_CONFIGURED -> log.debug("Screenshot not taken, Browser Run is not set up urlId={}", request.urlId());
        }
    }

    private void store(final Request request, final PageSnapshot snapshot) {
        final var takenAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        final var stored = Boolean.TRUE.equals(transactionOperations.execute(status -> {
            if (linkInspectionRepository.recordTaken(request.urlId(), request.requestedAt(), takenAt, request.longUrl(), snapshot.title(),
                    snapshot.status()) == 0) {
                return false;
            }
            linkScreenshotRepository.deleteByUrlId(request.urlId());
            linkScreenshotRepository.save(LinkScreenshot.builder().urlId(request.urlId()).image(snapshot.image()).contentType(snapshot.contentType()).build());
            return true;
        }));
        if (stored) {
            log.info("Screenshot stored urlId={} bytes={}", request.urlId(), snapshot.image().length);
        } else {
            log.info("Screenshot dropped, the link changed or was asked again while it was taken urlId={}", request.urlId());
        }
    }

    private void record(final Request request, final InspectionState state, final String failure) {
        final var updated = transactionOperations.execute(status ->
                linkInspectionRepository.recordOutcome(request.urlId(), request.requestedAt(), state, failure));
        if (updated == null || updated == 0) {
            log.info("Screenshot outcome dropped, the link changed or was asked again meanwhile urlId={}", request.urlId());
        } else if (state == InspectionState.FAILED) {
            log.info("Screenshot failed urlId={}", request.urlId());
        }
    }

    private static void pause(final AtomicReference<Instant> pause, final Instant until) {
        pause.accumulateAndGet(until, (current, candidate) -> current.isAfter(candidate) ? current : candidate);
    }

    private static void forgetFailureUnlessHead(final AtomicReference<Attempt> failure, final Optional<Attempt> head) {
        failure.updateAndGet(previous -> head.filter(attempt -> attempt.equals(previous)).orElse(null));
    }

    private Instant nextMidnightUtc() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private record Attempt(long urlId, LocalDateTime requestedAt) {
    }

    private record Lookup(long urlId, LocalDateTime requestedAt, String longUrl, String finalUrl, int nextCandidate) {

        static Lookup of(final LinkInspection inspection, final Url url) {
            return new Lookup(inspection.getUrlId(), inspection.getVtRequestedAt(), url.getLongUrl(), url.getFinalUrl(),
                    Math.max(0, Objects.requireNonNullElse(inspection.getVtNextCandidate(), 0)));
        }

        Attempt attempt() {
            return new Attempt(urlId, requestedAt);
        }

    }

    private record Request(long urlId, LocalDateTime requestedAt, InspectionRequester requestedBy, String longUrl, boolean blocked) {

        static Request of(final LinkInspection inspection, final Url url) {
            final var held = url.getThreatType() == null && url.getBlockReason() != null && BlockReason.HOLDS.contains(url.getBlockReason());
            return new Request(inspection.getUrlId(), inspection.getScreenshotRequestedAt(), inspection.getScreenshotRequestedBy(), url.getLongUrl(),
                    !url.isActive() && !held);
        }

        Attempt attempt() {
            return new Attempt(urlId, requestedAt);
        }

    }

}
