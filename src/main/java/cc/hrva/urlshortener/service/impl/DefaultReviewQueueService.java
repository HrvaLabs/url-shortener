package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.specification.LinkReviewSpecification;
import cc.hrva.urlshortener.service.ReviewQueueService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("flaggedAt"), Sort.Order.desc("id"));

    private final LinkReviewRepository linkReviewRepository;

    @Override
    @Transactional
    public void flag(final Url url, final List<String> reasons) {
        final var incoming = normalized(reasons);
        linkReviewRepository.findByUrlAndResolvedAtIsNull(url).ifPresentOrElse(
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
        linkReviewRepository.save(LinkReview.builder()
                .url(url)
                .reasons(joined(incoming))
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
    public void resolveOpen(final Url url, final ReviewResolution resolution, final String resolvedBy) {
        linkReviewRepository.findByUrlAndResolvedAtIsNull(url).ifPresent(review -> {
            review.setResolution(resolution);
            review.setResolvedBy(resolvedBy);
            review.setResolvedAt(LocalDateTime.now());
            linkReviewRepository.save(review);
            log.info("Review resolved urlId={} resolution={}", url.getId(), resolution);
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
