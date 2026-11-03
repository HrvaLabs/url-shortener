package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.client.BrowserRunClient;
import cc.hrva.urlshortener.client.PageSnapshot;
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
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final LinkInspectionRepository linkInspectionRepository;
    private final LinkScreenshotRepository linkScreenshotRepository;
    private final UrlRepository urlRepository;
    private final BrowserRunClient browserRunClient;
    private final SettingsService settingsService;
    private final AppProperties appProperties;
    private final TransactionOperations transactionOperations;
    private final Clock clock;
    private final AtomicReference<Instant> pausedUntil = new AtomicReference<>(Instant.MIN);
    private final AtomicReference<Attempt> failedBefore = new AtomicReference<>();

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
        if (next != null) {
            next.ifPresent(this::takeOrCountTheFailure);
        }
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
        pausedUntil.set(clock.instant().plus(PAUSE_AFTER_EXCEPTION));
        log.warn("Screenshot run failed, paused for a minute urlId={} exception={}", urlId, exception.getClass().getName());
    }

    private void failTheSecondTime(final Request request) {
        final var attempt = new Attempt(request.urlId(), request.requestedAt());
        if (!attempt.equals(failedBefore.getAndSet(attempt))) {
            return;
        }
        failedBefore.set(null);
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
                record(request, InspectionState.PENDING, DAILY_LIMIT);
                pausedUntil.set(nextMidnightUtc());
                log.info("Screenshots paused until midnight UTC, today's browser time is used up urlId={}", request.urlId());
            }
            case RATE_LIMITED -> {
                pausedUntil.set(clock.instant().plus(snapshot.retryAfter()));
                log.info("Screenshots paused for Cloudflare's rate limit seconds={} urlId={}", snapshot.retryAfter().toSeconds(), request.urlId());
            }
            case TOKEN_REFUSED -> {
                record(request, InspectionState.PENDING, TOKEN_REFUSED);
                pausedUntil.set(clock.instant().plus(PAUSE_AFTER_REFUSED_TOKEN));
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

    private Instant nextMidnightUtc() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private record Attempt(long urlId, LocalDateTime requestedAt) {
    }

    private record Request(long urlId, LocalDateTime requestedAt, InspectionRequester requestedBy, String longUrl, boolean blocked) {

        static Request of(final LinkInspection inspection, final Url url) {
            final var held = url.getThreatType() == null && url.getBlockReason() != null && BlockReason.HOLDS.contains(url.getBlockReason());
            return new Request(inspection.getUrlId(), inspection.getScreenshotRequestedAt(), inspection.getScreenshotRequestedBy(), url.getLongUrl(),
                    !url.isActive() && !held);
        }

    }

}
