package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.bio.BioPageReviewEntryResponse;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageReview;
import cc.hrva.urlshortener.model.enums.BioPageReviewResolution;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.repository.BioPageReviewRepository;
import cc.hrva.urlshortener.service.BioPageReviewService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AlertedBioPage;
import cc.hrva.urlshortener.util.ReviewReasons;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultBioPageReviewService implements BioPageReviewService {

    private static final String REASON_SEPARATOR = "\n";
    private static final int MAX_VISITOR_REPORTS_PER_REVIEW = 20;

    private final BioPageReviewRepository bioPageReviewRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public void flag(final BioPage page, final List<String> reasons) {
        final var incoming = normalized(reasons);
        if (incoming.isEmpty()) {
            return;
        }
        if (page.getHiddenAt() != null) {
            log.info("Bio page is hidden, not queued for review pageId={}", page.getId());
            return;
        }
        bioPageReviewRepository.findFirstByPageAndResolvedAtIsNullOrderByFlaggedAtDesc(page).ifPresentOrElse(
                review -> extend(review, incoming, true),
                () -> openUnlessReviewedFine(page, incoming));
    }

    private boolean extend(final BioPageReview review, final List<String> incoming, final boolean systemFlagged) {
        final var known = reasonsOf(review);
        final var added = incoming.stream().filter(reason -> !known.contains(reason)).toList();
        final var newlySystemFlagged = systemFlagged && !review.isSystemFlagged();
        if (added.isEmpty() && !newlySystemFlagged) {
            return false;
        }
        review.setReasons(joined(Stream.concat(known.stream(), added.stream()).toList()));
        review.setSystemFlagged(review.isSystemFlagged() || systemFlagged);
        bioPageReviewRepository.save(review);
        log.info("Bio page review got new reasons pageId={} reviewId={}", review.getPage().getId(), review.getId());
        return !added.isEmpty();
    }

    private void openUnlessReviewedFine(final BioPage page, final List<String> incoming) {
        if (reviewedFineFor(page, incoming)) {
            return;
        }
        openReview(page, incoming, true);
        eventPublisher.publishEvent(new AdminAlertEvent.BioPageReviewOpened(AlertedBioPage.of(page), incoming));
    }

    private void openReview(final BioPage page, final List<String> reasons, final boolean systemFlagged) {
        bioPageReviewRepository.save(BioPageReview.builder()
                .page(page)
                .reasons(joined(reasons))
                .flaggedAt(LocalDateTime.now(clock))
                .systemFlagged(systemFlagged)
                .build());
        log.info("Bio page queued for review pageId={}", page.getId());
    }

    private boolean reviewedFineFor(final BioPage page, final List<String> incoming) {
        return bioPageReviewRepository.findFirstByPageOrderByFlaggedAtDesc(page)
                .filter(review -> review.getResolution() == BioPageReviewResolution.OK)
                .map(review -> reasonsOf(review).containsAll(incoming))
                .orElse(false);
    }

    @Override
    @Transactional
    public void report(final BioPage page, final ReportCategory category, final String details) {
        final var incoming = normalized(List.of(reportReason(category, details)));
        log.info("Visitor report received for a bio page pageId={} category={}", page.getId(), category);
        bioPageReviewRepository.findFirstByPageAndResolvedAtIsNullOrderByFlaggedAtDesc(page).ifPresentOrElse(
                review -> extendUnlessFullOfReports(review, incoming, category, details),
                () -> openFromReport(page, incoming, category, details));
    }

    private void extendUnlessFullOfReports(final BioPageReview review, final List<String> incoming, final ReportCategory category,
                                           final String details) {
        final var visitorReports = reasonsOf(review).stream().filter(reason -> reason.startsWith(ReviewReasons.VISITOR_REPORT)).count();
        if (visitorReports >= MAX_VISITOR_REPORTS_PER_REVIEW) {
            log.info("Bio page review already holds the most visitor reports pageId={} reviewId={}", review.getPage().getId(), review.getId());
            return;
        }
        if (extend(review, incoming, false)) {
            eventPublisher.publishEvent(new AdminAlertEvent.BioPageReported(AlertedBioPage.of(review.getPage()), category, details, false));
        }
    }

    private void openFromReport(final BioPage page, final List<String> incoming, final ReportCategory category, final String details) {
        openReview(page, incoming, false);
        eventPublisher.publishEvent(new AdminAlertEvent.BioPageReported(AlertedBioPage.of(page), category, details, true));
    }

    private static String reportReason(final ReportCategory category, final String details) {
        final var reason = ReviewReasons.VISITOR_REPORT + ": " + category.name().toLowerCase(Locale.ROOT);
        return StringUtils.isBlank(details) ? reason : reason + ": " + details;
    }

    @Override
    @Transactional
    public void resolveOpen(final BioPage page, final BioPageReviewResolution resolution, final String resolvedBy) {
        final var resolvedAt = LocalDateTime.now(clock);
        bioPageReviewRepository.findByPageAndResolvedAtIsNull(page).forEach(review -> {
            review.setResolution(resolution);
            review.setResolvedBy(resolvedBy);
            review.setResolvedAt(resolvedAt);
            bioPageReviewRepository.save(review);
            log.info("Bio page review resolved pageId={} reviewId={} resolution={}", page.getId(), review.getId(), resolution);
        });
    }

    @Override
    public Page<BioPageReviewEntryResponse> openEntries(final Pageable pageable) {
        final var unsorted = pageable.isPaged() ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()) : Pageable.unpaged();
        return bioPageReviewRepository.findOpen(unsorted).map(DefaultBioPageReviewService::entryOf);
    }

    private static BioPageReviewEntryResponse entryOf(final BioPageReview review) {
        final var page = review.getPage();
        final var owner = page.getOwner();
        return new BioPageReviewEntryResponse(
                review.getId(),
                page.getId(),
                page.getHandle(),
                page.getDisplayName(),
                owner.getId(),
                owner.getEmail(),
                page.getHiddenAt() != null,
                reasonsOf(review),
                review.getFlaggedAt());
    }

    @Override
    public long openCount() {
        return bioPageReviewRepository.countByResolvedAtIsNull();
    }

    @Override
    public Set<Long> openPageIds(final Collection<Long> pageIds) {
        if (pageIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(bioPageReviewRepository.findOpenPageIds(pageIds));
    }

    private static List<String> normalized(final List<String> reasons) {
        return reasons.stream()
                .map(StringUtils::normalizeSpace)
                .filter(StringUtils::isNotEmpty)
                .distinct()
                .toList();
    }

    private static List<String> reasonsOf(final BioPageReview review) {
        return review.getReasons().lines().map(String::strip).filter(StringUtils::isNotEmpty).toList();
    }

    private static String joined(final List<String> reasons) {
        return String.join(REASON_SEPARATOR, reasons);
    }

}
