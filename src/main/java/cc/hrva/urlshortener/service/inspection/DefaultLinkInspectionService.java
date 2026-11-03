package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.dto.inspection.LinkInspectionResponse;
import cc.hrva.urlshortener.dto.inspection.ScreenshotView;
import cc.hrva.urlshortener.exception.ScreenshotsUnavailableException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.LinkInspection;
import cc.hrva.urlshortener.model.LinkScreenshot;
import cc.hrva.urlshortener.model.enums.InspectionRequester;
import cc.hrva.urlshortener.model.enums.InspectionState;
import cc.hrva.urlshortener.repository.LinkInspectionRepository;
import cc.hrva.urlshortener.repository.LinkScreenshotRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.alert.LinkReviewOpened;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.RedirectChains;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final LinkInspectionRepository linkInspectionRepository;
    private final LinkScreenshotRepository linkScreenshotRepository;
    private final UrlRepository urlRepository;
    private final SettingsService settingsService;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewOpened(final LinkReviewOpened event) {
        try {
            requestScreenshot(event.urlId());
        } catch (final RuntimeException exception) {
            log.warn("Screenshot request failed urlId={} exception={}", event.urlId(), exception.getClass().getName());
        }
    }

    private void request(final long urlId, final InspectionRequester requester) {
        newTransaction().executeWithoutResult(status -> {
            if (!urlRepository.existsById(urlId)) {
                log.info("Screenshot request skipped, the link is gone urlId={}", urlId);
                return;
            }
            final var now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
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
        final var configured = screenshotsOn();
        final var screenshot = linkInspectionRepository.findById(urlId)
                .map(found -> viewOf(found, configured))
                .orElseGet(() -> ScreenshotView.none(configured));
        return new LinkInspectionResponse(urlId, url.getLongUrl(), RedirectChains.split(url.getRedirectChain()), screenshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LinkScreenshot> screenshot(final long urlId) {
        return linkScreenshotRepository.findById(urlId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ScreenshotView> summaries(final Collection<Long> urlIds) {
        if (urlIds.isEmpty()) {
            return Map.of();
        }
        final var configured = screenshotsOn();
        final var found = linkInspectionRepository.findAllById(urlIds).stream()
                .collect(Collectors.toMap(LinkInspection::getUrlId, Function.identity()));
        final var summaries = new LinkedHashMap<Long, ScreenshotView>();
        urlIds.forEach(urlId -> summaries.put(urlId, Optional.ofNullable(found.get(urlId))
                .map(inspection -> viewOf(inspection, configured))
                .orElseGet(() -> ScreenshotView.none(configured))));
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
