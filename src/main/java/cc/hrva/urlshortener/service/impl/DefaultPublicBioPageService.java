package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.dto.bio.PublicBioPageResponse;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.BioPageViewStatsRepository;
import cc.hrva.urlshortener.service.PublicBioPageService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.analytics.UniqueVisitorCounter;
import cc.hrva.urlshortener.service.analytics.UserAgentClassifier;
import cc.hrva.urlshortener.service.bio.BioHandles;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultPublicBioPageService implements PublicBioPageService {

    private static final String NOT_AVAILABLE = "This page isn't available.";
    private static final String VIEW_LIMIT = "bio-view";
    private static final int VIEWS_PER_NETWORK = 60;
    private static final Duration VIEW_WINDOW = Duration.ofMinutes(1);

    private final BioPageRepository bioPageRepository;
    private final BioPageResponses bioPageResponses;
    private final BioPageViewStatsRepository bioPageViewStatsRepository;
    private final UniqueVisitorCounter uniqueVisitorCounter;
    private final RateLimiter rateLimiter;
    private final AnalyticsDays analyticsDays;

    @Override
    public PublicBioPageResponse find(final String handle) {
        return publicPage(handle)
                .map(bioPageResponses::publicView)
                .orElseThrow(() -> new BioPageNotFoundException(NOT_AVAILABLE));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recordView(final String handle, final ClickRequest click) {
        final Optional<BioPage> page;
        try {
            page = publicPage(handle);
        } catch (final RuntimeException exception) {
            log.warn("Bio page view dropped pageId=unknown exception={}", exception.getClass().getName());
            return;
        }
        page.ifPresent(publicPage -> record(publicPage.getId(), click));
    }

    private void record(final long pageId, final ClickRequest click) {
        try {
            if (!withinViewLimit(pageId, click)) {
                return;
            }
            final var day = analyticsDays.today();
            if (UserAgentClassifier.isBot(click.userAgent())) {
                bioPageViewStatsRepository.increment(pageId, day, 0, 1);
            } else if (uniqueVisitorCounter.isNewBioPageVisitor(pageId, day, click)) {
                bioPageViewStatsRepository.increment(pageId, day, 1, 0);
            }
        } catch (final RuntimeException exception) {
            log.warn("Bio page view dropped pageId={} exception={}", pageId, exception.getClass().getName());
        }
    }

    private boolean withinViewLimit(final long pageId, final ClickRequest click) {
        try {
            return rateLimiter.tryAcquire(RateLimiter.clientKey(VIEW_LIMIT, click.clientIp()), VIEWS_PER_NETWORK, VIEW_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Bio page view rate limit unavailable, counting the view pageId={}", pageId);
            return true;
        }
    }

    private Optional<BioPage> publicPage(final String handle) {
        final var normalized = BioHandles.normalize(handle);
        if (BioHandles.formatError(normalized).isPresent()) {
            return Optional.empty();
        }
        return bioPageRepository.findByHandleWithOwner(normalized).filter(BioPage::isPublic);
    }

}
