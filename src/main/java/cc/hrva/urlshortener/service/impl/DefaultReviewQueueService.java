package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.specification.LinkReviewSpecification;
import cc.hrva.urlshortener.service.ReviewQueueService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultReviewQueueService implements ReviewQueueService {

    private static final String REASON_SEPARATOR = "\n";
    private static final String REPORTED_BY_A_VISITOR = "reported by a visitor";
    private static final int MAX_VISITOR_REPORTS_PER_REVIEW = 20;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("flaggedAt"), Sort.Order.desc("id"));

    private final LinkReviewRepository linkReviewRepository;
    private final UrlRepository urlRepository;

    @Override
    @Transactional
    public void flag(final Url url, final List<String> reasons) {
        if (urlRepository.findById(url.getId()).filter(Url::isActive).isEmpty()) {
            log.info("Link is no longer active, not queued for review urlId={}", url.getId());
            return;
        }
        final var incoming = normalized(reasons);
        linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url).ifPresentOrElse(
                review -> extend(review, incoming),
                () -> openUnlessReviewedFine(url, incoming));
    }

    private void extend(final LinkReview review, final List<String> incoming) {
        final var known = reasonsOf(review);
        final var added = incoming.stream().filter(reason -> !known.contains(reason)).toList();
        if (added.isEmpty()) {
            return;
        }
        review.setReasons(joined(Stream.concat(known.stream(), added.stream()).toList()));
        linkReviewRepository.save(review);
        log.info("Review got new reasons urlId={} reviewId={}", review.getUrl().getId(), review.getId());
    }

    private void openUnlessReviewedFine(final Url url, final List<String> incoming) {
        if (reviewedFineFor(url, incoming)) {
            return;
        }
        openReview(url, incoming);
    }

    private void openReview(final Url url, final List<String> reasons) {
        linkReviewRepository.save(LinkReview.builder()
                .url(url)
                .reasons(joined(reasons))
                .build());
        log.info("Link queued for review urlId={}", url.getId());
    }

    private boolean reviewedFineFor(final Url url, final List<String> incoming) {
        return linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url)
                .filter(review -> review.getResolution() == ReviewResolution.OK)
                .map(review -> reasonsOf(review).containsAll(incoming))
                .orElse(false);
    }

    @Override
    @Transactional
    public void report(final Url url, final ReportCategory category, final String details) {
        final var incoming = normalized(List.of(reportReason(category, details)));
        log.info("Visitor report received urlId={} category={}", url.getId(), category);
        linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url).ifPresentOrElse(
                review -> extendUnlessFullOfReports(review, incoming),
                () -> openReview(url, incoming));
    }

    private void extendUnlessFullOfReports(final LinkReview review, final List<String> incoming) {
        final var visitorReports = reasonsOf(review).stream().filter(reason -> reason.startsWith(REPORTED_BY_A_VISITOR)).count();
        if (visitorReports >= MAX_VISITOR_REPORTS_PER_REVIEW) {
            log.info("Review already holds the most visitor reports urlId={} reviewId={}", review.getUrl().getId(), review.getId());
            return;
        }
        extend(review, incoming);
    }

    private static String reportReason(final ReportCategory category, final String details) {
        final var reason = REPORTED_BY_A_VISITOR + ": " + category.name().toLowerCase(Locale.ROOT);
        return StringUtils.isBlank(details) ? reason : reason + ": " + details;
    }

    @Override
    @Transactional
    public void resolveOpen(final Url url, final ReviewResolution resolution, final String resolvedBy) {
        final var resolvedAt = LocalDateTime.now();
        linkReviewRepository.findByUrlAndResolvedAtIsNull(url).forEach(review -> {
            review.setResolution(resolution);
            review.setResolvedBy(resolvedBy);
            review.setResolvedAt(resolvedAt);
            linkReviewRepository.save(review);
            log.info("Review resolved urlId={} reviewId={} resolution={}", url.getId(), review.getId(), resolution);
        });
    }

    @Override
    public Page<LinkReview> open(final Pageable pageable, final String reason) {
        final var filters = new ArrayList<Specification<LinkReview>>();
        filters.add(LinkReviewSpecification.isOpen());
        if (StringUtils.isNotBlank(reason)) {
            filters.add(LinkReviewSpecification.reasonContains(reason.trim()));
        }
        return linkReviewRepository.findAll(Specification.allOf(filters), newestFirst(pageable));
    }

    @Override
    public long openCount() {
        return linkReviewRepository.countByResolvedAtIsNull();
    }

    @Override
    public long openReportCount() {
        return linkReviewRepository.countByResolvedAtIsNullAndReasonsContaining(REPORTED_BY_A_VISITOR);
    }

    private static Pageable newestFirst(final Pageable pageable) {
        return pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST)
                : Pageable.unpaged(NEWEST_FIRST);
    }

    private static List<String> normalized(final List<String> reasons) {
        return reasons.stream()
                .map(StringUtils::normalizeSpace)
                .filter(StringUtils::isNotEmpty)
                .distinct()
                .toList();
    }

    private static List<String> reasonsOf(final LinkReview review) {
        return review.getReasons().lines().map(String::strip).filter(StringUtils::isNotEmpty).toList();
    }

    private static String joined(final List<String> reasons) {
        return String.join(REASON_SEPARATOR, reasons);
    }

}
