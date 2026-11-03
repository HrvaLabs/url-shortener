package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.audit.AuditSubjects;
import cc.hrva.urlshortener.client.VirusTotalClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.inspection.InspectionSummary;
import cc.hrva.urlshortener.dto.inspection.LinkInspectionResponse;
import cc.hrva.urlshortener.dto.inspection.ScreenshotView;
import cc.hrva.urlshortener.dto.inspection.VirusTotalView;
import cc.hrva.urlshortener.exception.ScreenshotsUnavailableException;
import cc.hrva.urlshortener.exception.ServiceBusyException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.VirusTotalLookupNotQueuedException;
import cc.hrva.urlshortener.exception.VirusTotalUnavailableException;
import cc.hrva.urlshortener.model.LinkInspection;
import cc.hrva.urlshortener.model.LinkScreenshot;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.InspectionRequester;
import cc.hrva.urlshortener.model.enums.InspectionState;
import cc.hrva.urlshortener.repository.LinkInspectionRepository;
import cc.hrva.urlshortener.repository.LinkScreenshotRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.alert.LinkReviewOpened;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.RedirectChains;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLinkInspectionService implements LinkInspectionService {

    private static final String LINK_NOT_FOUND = "Link not found.";
    private static final String NOT_SET_UP = "Screenshots aren't set up.";
    private static final String VIRUSTOTAL_NOT_SET_UP = "VirusTotal isn't set up.";
    private static final String VIRUSTOTAL_USED_UP = "Today's VirusTotal lookups are used up. Try again after midnight UTC.";
    private static final String TRY_AGAIN = " Try again in a minute.";
    private static final String MAY_HAVE_REACHED = " The link may have reached it.";
    private static final String VIRUSTOTAL_REFUSING = "VirusTotal is refusing requests for now." + TRY_AGAIN;
    private static final String LOOKUP_NOT_QUEUED = "The link was sent to VirusTotal, but its lookup couldn't be queued.";
    private static final String VIRUSTOTAL_UNCOUNTED = "hrva.cc can't count today's VirusTotal lookups right now. Try again in a minute.";
    private static final String TARGET_URL = "Url";
    private static final Duration TRY_AGAIN_IN = Duration.ofMinutes(1);
    private static final Duration LOOKUP_AFTER_SUBMIT = Duration.ofSeconds(60);

    private final LinkInspectionRepository linkInspectionRepository;
    private final LinkScreenshotRepository linkScreenshotRepository;
    private final UrlRepository urlRepository;
    private final SettingsService settingsService;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final VirusTotalClient virusTotalClient;
    private final RateLimiter rateLimiter;
    private final AppProperties appProperties;
    private final AuditLogService auditLogService;
    private final AuditSubjects auditSubjects;

    @Override
    public void requestScreenshot(final long urlId) {
        if (!screenshotsOn()) {
            return;
        }
        try {
            request(urlId, InspectionRequester.QUEUE);
        } catch (final DataIntegrityViolationException exception) {
            log.info("Screenshot request raced another one, retrying once urlId={}", urlId);
            try {
                request(urlId, InspectionRequester.QUEUE);
            } catch (final DataIntegrityViolationException again) {
                log.info("Screenshot request skipped, the link is gone urlId={}", urlId);
            }
        }
    }

    @Override
    public void takeScreenshot(final long urlId) {
        if (!urlRepository.existsById(urlId)) {
            throw new UrlNotFoundException(LINK_NOT_FOUND);
        }
        if (!screenshotsOn()) {
            throw new ScreenshotsUnavailableException(NOT_SET_UP);
        }
        try {
            request(urlId, InspectionRequester.ADMIN);
        } catch (final DataIntegrityViolationException exception) {
            log.info("Screenshot request raced another one, retrying once urlId={}", urlId);
            request(urlId, InspectionRequester.ADMIN);
        }
    }

    @Override
    public void requestVirusTotal(final long urlId) {
        if (!virusTotalOn()) {
            return;
        }
        try {
            requestLookup(urlId);
        } catch (final DataIntegrityViolationException exception) {
            log.info("VirusTotal lookup request raced another one, retrying once urlId={}", urlId);
            try {
                requestLookup(urlId);
            } catch (final DataIntegrityViolationException again) {
                log.info("VirusTotal lookup request skipped, the link is gone urlId={}", urlId);
            }
        }
    }

    @Override
    public VirusTotalView submitToVirusTotal(final long urlId, final String performedBy) {
        final var targetIdentifier = String.valueOf(urlId);
        final var subject = auditSubjects.describe(TARGET_URL, targetIdentifier).orElse("");
        try {
            send(urlId);
        } catch (final RuntimeException exception) {
            auditLogService.logFailure(performedBy, AuditAction.SUBMIT_TO_VIRUSTOTAL, TARGET_URL, targetIdentifier,
                    Stream.of(subject, exception.getMessage()).filter(StringUtils::isNotEmpty).collect(Collectors.joining(", ")));
            throw exception;
        }
        auditLogService.log(performedBy, AuditAction.SUBMIT_TO_VIRUSTOTAL, TARGET_URL, targetIdentifier, subject);
        try {
            return lookUpAfterTheAnalysisRetryingOnce(urlId);
        } catch (final RuntimeException exception) {
            log.warn("Link sent to VirusTotal, but its lookup could not be queued urlId={} exception={}", urlId, exception.getClass().getName());
            throw new VirusTotalLookupNotQueuedException(LOOKUP_NOT_QUEUED);
        }
    }

    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewOpened(final LinkReviewOpened event) {
        try {
            requestScreenshot(event.urlId());
        } catch (final RuntimeException exception) {
            log.warn("Screenshot request failed urlId={} exception={}", event.urlId(), exception.getClass().getName());
        }
        try {
            requestVirusTotal(event.urlId());
        } catch (final RuntimeException exception) {
            log.warn("VirusTotal lookup request failed urlId={} exception={}", event.urlId(), exception.getClass().getName());
        }
    }

    private void send(final long urlId) {
        final var url = urlRepository.findById(urlId).orElseThrow(() -> new UrlNotFoundException(LINK_NOT_FOUND));
        if (!virusTotalOn()) {
            throw new VirusTotalUnavailableException(VIRUSTOTAL_NOT_SET_UP);
        }
        if (!withinTheDailyLimit()) {
            throw new VirusTotalUnavailableException(VIRUSTOTAL_USED_UP);
        }
        final var report = virusTotalClient.submit(url.getLongUrl());
        switch (report.outcome()) {
            case SUBMITTED -> log.info("Link sent to VirusTotal urlId={}", urlId);
            case RATE_LIMITED -> throw new ServiceBusyException(VIRUSTOTAL_REFUSING, TRY_AGAIN_IN);
            case UNAVAILABLE -> throw new ServiceBusyException(report.failure() + MAY_HAVE_REACHED + TRY_AGAIN, TRY_AGAIN_IN);
            case KEY_REFUSED, FAILED -> throw new VirusTotalUnavailableException(report.failure());
            case NOT_CONFIGURED -> throw new VirusTotalUnavailableException(VIRUSTOTAL_NOT_SET_UP);
            case FOUND, NOT_FOUND -> throw new IllegalStateException("VirusTotal answered a submit with " + report.outcome());
        }
    }

    private VirusTotalView lookUpAfterTheAnalysisRetryingOnce(final long urlId) {
        try {
            return lookUpAfterTheAnalysis(urlId);
        } catch (final DataIntegrityViolationException exception) {
            log.info("VirusTotal lookup after a submit raced another request, retrying once urlId={}", urlId);
            return lookUpAfterTheAnalysis(urlId);
        }
    }

    private boolean withinTheDailyLimit() {
        try {
            return rateLimiter.increment(VirusTotalQuota.keyOfToday(clock), VirusTotalQuota.KEPT_FOR)
                    <= appProperties.getInspection().getVirusTotalDailyLimit();
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("VirusTotal submit refused, today's count is unavailable");
            throw new ServiceBusyException(VIRUSTOTAL_UNCOUNTED, TRY_AGAIN_IN);
        }
    }

    private VirusTotalView lookUpAfterTheAnalysis(final long urlId) {
        return newTransaction().execute(status -> {
            if (!urlRepository.existsById(urlId)) {
                throw new UrlNotFoundException(LINK_NOT_FOUND);
            }
            final var now = now();
            final var requestedAt = now.plus(LOOKUP_AFTER_SUBMIT);
            if (linkInspectionRepository.queueVirusTotalAfterSubmit(urlId, requestedAt, now) == 0) {
                linkInspectionRepository.saveAndFlush(LinkInspection.builder().urlId(urlId).vtState(InspectionState.PENDING).vtRequestedAt(requestedAt)
                        .vtSubmittedAt(now).build());
            }
            return linkInspectionRepository.findById(urlId)
                    .map(inspection -> virusTotalViewOf(inspection, true))
                    .orElseThrow(() -> new IllegalStateException("The VirusTotal lookup was written but can't be read back"));
        });
    }

    private void requestLookup(final long urlId) {
        newTransaction().executeWithoutResult(status -> {
            if (!urlRepository.existsById(urlId)) {
                log.info("VirusTotal lookup request skipped, the link is gone urlId={}", urlId);
                return;
            }
            final var inspection = linkInspectionRepository.findById(urlId).orElseGet(() -> LinkInspection.builder().urlId(urlId).build());
            if (inspection.getVtState() == InspectionState.PENDING) {
                return;
            }
            inspection.setVtState(InspectionState.PENDING);
            inspection.setVtRequestedAt(now());
            inspection.setVtNextCandidate(null);
            inspection.setVtFailure(null);
            linkInspectionRepository.saveAndFlush(inspection);
            log.info("VirusTotal lookup requested urlId={}", urlId);
        });
    }

    private void request(final long urlId, final InspectionRequester requester) {
        newTransaction().executeWithoutResult(status -> {
            if (!urlRepository.existsById(urlId)) {
                log.info("Screenshot request skipped, the link is gone urlId={}", urlId);
                return;
            }
            final var now = now();
            final var inspection = linkInspectionRepository.findById(urlId).orElseGet(() -> LinkInspection.builder().urlId(urlId).build());
            if (inspection.getScreenshotState() == InspectionState.PENDING) {
                if (requester == InspectionRequester.ADMIN && inspection.getScreenshotRequestedBy() != InspectionRequester.ADMIN) {
                    inspection.setScreenshotRequestedBy(InspectionRequester.ADMIN);
                    linkInspectionRepository.save(inspection);
                }
                return;
            }
            inspection.setScreenshotState(InspectionState.PENDING);
            inspection.setScreenshotRequestedAt(now);
            inspection.setScreenshotRequestedBy(requester);
            inspection.setScreenshotFailure(null);
            linkInspectionRepository.saveAndFlush(inspection);
            log.info("Screenshot requested urlId={} requestedBy={}", urlId, requester);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public LinkInspectionResponse inspection(final long urlId) {
        final var url = urlRepository.findById(urlId).orElseThrow(() -> new UrlNotFoundException(LINK_NOT_FOUND));
        final var summary = summaryOf(linkInspectionRepository.findById(urlId), screenshotsOn(), virusTotalOn());
        return new LinkInspectionResponse(urlId, url.getLongUrl(), RedirectChains.split(url.getRedirectChain()), summary.screenshot(),
                summary.virusTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LinkScreenshot> screenshot(final long urlId) {
        return linkScreenshotRepository.findById(urlId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, InspectionSummary> summaries(final Collection<Long> urlIds) {
        if (urlIds.isEmpty()) {
            return Map.of();
        }
        final var screenshotsConfigured = screenshotsOn();
        final var virusTotalConfigured = virusTotalOn();
        final var found = linkInspectionRepository.findAllById(urlIds).stream()
                .collect(Collectors.toMap(LinkInspection::getUrlId, Function.identity()));
        final var summaries = new LinkedHashMap<Long, InspectionSummary>();
        urlIds.forEach(urlId -> summaries.put(urlId, summaryOf(Optional.ofNullable(found.get(urlId)), screenshotsConfigured, virusTotalConfigured)));
        return summaries;
    }

    @Override
    @Transactional
    public void dropFor(final long urlId) {
        final var inspections = linkInspectionRepository.deleteByUrlId(urlId);
        final var screenshots = linkScreenshotRepository.deleteByUrlId(urlId);
        if (screenshots + inspections > 0) {
            log.info("Link inspection dropped urlId={}", urlId);
        }
    }

    private boolean screenshotsOn() {
        return settingsService.isOnAndAvailable(SettingKey.LINK_SCREENSHOTS);
    }

    private boolean virusTotalOn() {
        return settingsService.isOnAndAvailable(SettingKey.VIRUSTOTAL_LOOKUPS);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static InspectionSummary summaryOf(final Optional<LinkInspection> inspection, final boolean screenshotsConfigured,
                                               final boolean virusTotalConfigured) {
        return new InspectionSummary(
                inspection.map(found -> viewOf(found, screenshotsConfigured)).orElseGet(() -> ScreenshotView.none(screenshotsConfigured)),
                inspection.map(found -> virusTotalViewOf(found, virusTotalConfigured)).orElseGet(() -> VirusTotalView.none(virusTotalConfigured)));
    }

    private static VirusTotalView virusTotalViewOf(final LinkInspection inspection, final boolean configured) {
        if (inspection.getVtState() == null) {
            return VirusTotalView.none(configured);
        }
        return VirusTotalView.of(inspection.getVtState(), inspection.getVtUrl(), inspection.getVtReportId(), inspection.getVtMalicious(),
                inspection.getVtSuspicious(), inspection.getVtHarmless(), inspection.getVtUndetected(), inspection.getVtAnalysedAt(),
                inspection.getVtCheckedAt(), inspection.getVtSubmittedAt(), inspection.getVtFailure(), configured);
    }

    private static ScreenshotView viewOf(final LinkInspection inspection, final boolean configured) {
        if (inspection.getScreenshotState() == null) {
            return ScreenshotView.none(configured);
        }
        return new ScreenshotView(inspection.getScreenshotState(), inspection.getScreenshotRequestedAt(), inspection.getScreenshotTakenAt(),
                inspection.getScreenshotUrl(), inspection.getPageTitle(), inspection.getPageStatus(), inspection.getScreenshotFailure(), configured);
    }

    private TransactionTemplate newTransaction() {
        final var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

}
