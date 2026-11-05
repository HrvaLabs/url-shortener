package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.ReviewEntryResponse;
import cc.hrva.urlshortener.dto.inspection.InspectionSummary;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.specification.LinkReviewSpecification;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AlertedLink;
import cc.hrva.urlshortener.service.alert.LinkReviewOpened;
import cc.hrva.urlshortener.service.inspection.LinkInspectionService;
import cc.hrva.urlshortener.service.moderation.ReviewWarningPage;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.util.RedirectChains;
import cc.hrva.urlshortener.util.ReviewReasons;
import cc.hrva.urlshortener.util.SortWhitelist;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
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
    private static final int MAX_VISITOR_REPORTS_PER_REVIEW = 20;
    private static final String FLAGGED_AT = "flaggedAt";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc(FLAGGED_AT), Sort.Order.desc("id"));
    private static final SortWhitelist ENTRY_SORTS = SortWhitelist.of(FLAGGED_AT)
            .byDefault(Sort.by(Sort.Order.desc(FLAGGED_AT)))
            .thenBy(Sort.Order.desc("id"));

    private final LinkReviewRepository linkReviewRepository;
    private final UrlRepository urlRepository;
    private final ScammerSignalService scammerSignalService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final ReviewWarningPage reviewWarningPage;
    private final LinkInspectionService linkInspectionService;

    @Override
    @Transactional
    public void flag(final Url url, final List<String> reasons) {
        queue(url, reasons, false);
    }

    @Override
    @Transactional
    public void notice(final Url url, final List<String> reasons) {
        queue(url, reasons, true);
    }

    private void queue(final Url url, final List<String> reasons, final boolean notice) {
        if (urlRepository.findById(url.getId()).filter(Url::isActive).isEmpty()) {
            log.info("Link is no longer active, not queued for review urlId={}", url.getId());
            return;
        }
        final var incoming = normalized(reasons);
        if (incoming.isEmpty()) {
            return;
        }
        linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url).ifPresentOrElse(
                review -> extend(review, url, incoming, !notice),
                () -> openUnlessReviewedFine(url, incoming, notice));
    }

    private boolean extend(final LinkReview review, final Url url, final List<String> incoming, final boolean fromFlag) {
        final var known = reasonsOf(review);
        final var added = incoming.stream().filter(reason -> !known.contains(reason)).toList();
        final var flagChanged = fromFlag && (!review.isSystemFlagged() || review.isNotice());
        final var destinationChanged = !Objects.equals(url.getLongUrl(), review.getCheckedUrl());
        if (added.isEmpty() && !flagChanged && !destinationChanged) {
            return false;
        }
        review.setReasons(joined(Stream.concat(known.stream(), added.stream()).toList()));
        if (fromFlag) {
            review.setSystemFlagged(true);
            review.setNotice(false);
        }
        review.setCheckedUrl(url.getLongUrl());
        linkReviewRepository.save(review);
        log.info("Review extended urlId={} reviewId={} notice={}", review.getUrl().getId(), review.getId(), review.isNotice());
        return !added.isEmpty();
    }

    private void openUnlessReviewedFine(final Url url, final List<String> incoming, final boolean notice) {
        if (reviewedFineFor(url, incoming)) {
            return;
        }
        openReview(url, incoming, true, notice);
        eventPublisher.publishEvent(new AdminAlertEvent.ReviewOpened(AlertedLink.of(url), incoming));
    }

    private void openReview(final Url url, final List<String> reasons, final boolean systemFlagged, final boolean notice) {
        linkReviewRepository.save(LinkReview.builder()
                .url(url)
                .reasons(joined(reasons))
                .systemFlagged(systemFlagged)
                .notice(notice)
                .checkedUrl(url.getLongUrl())
                .build());
        log.info("Link queued for review urlId={} notice={}", url.getId(), notice);
        reviewWarningPage.showWhileInReview(url);
        eventPublisher.publishEvent(new LinkReviewOpened(url.getId()));
    }

    private boolean reviewedFineFor(final Url url, final List<String> incoming) {
        final var deciding = incoming.stream().filter(reason -> !SuspiciousUrlService.isSupporting(reason)).toList();
        return linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url)
                .filter(review -> review.getResolution() == ReviewResolution.OK)
                .filter(review -> reasonsOf(review).containsAll(deciding))
                .filter(review -> incoming.stream().noneMatch(ReviewReasons::isSignInForm) || coversDestination(review, url))
                .isPresent();
    }

    private static boolean coversDestination(final LinkReview review, final Url url) {
        return review.getCheckedUrl() != null && review.getCheckedUrl().equals(url.getLongUrl());
    }

    @Override
    @Transactional
    public void inspectAgainIfOpen(final Url url) {
        linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url).ifPresent(review -> {
            log.info("Repointed link is still in review, inspecting it again urlId={}", url.getId());
            eventPublisher.publishEvent(new LinkReviewOpened(url.getId()));
        });
    }

    @Override
    @Transactional
    public void report(final Url url, final ReportCategory category, final String details) {
        final var incoming = normalized(List.of(reportReason(category, details)));
        log.info("Visitor report received urlId={} category={}", url.getId(), category);
        linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url).ifPresentOrElse(
                review -> extendUnlessFullOfReports(review, incoming, category, details),
                () -> openFromReport(url, incoming, category, details));
    }

    private void extendUnlessFullOfReports(final LinkReview review, final List<String> incoming, final ReportCategory category,
                                           final String details) {
        final var visitorReports = reasonsOf(review).stream().filter(reason -> reason.startsWith(ReviewReasons.VISITOR_REPORT)).count();
        if (visitorReports >= MAX_VISITOR_REPORTS_PER_REVIEW) {
            log.info("Review already holds the most visitor reports urlId={} reviewId={}", review.getUrl().getId(), review.getId());
            return;
        }
        if (extend(review, review.getUrl(), incoming, false)) {
            eventPublisher.publishEvent(new AdminAlertEvent.VisitorReported(AlertedLink.of(review.getUrl()), category, details, false));
        }
    }

    private void openFromReport(final Url url, final List<String> incoming, final ReportCategory category, final String details) {
        openReview(url, incoming, false, false);
        eventPublisher.publishEvent(new AdminAlertEvent.VisitorReported(AlertedLink.of(url), category, details, true));
    }

    private static String reportReason(final ReportCategory category, final String details) {
        final var reason = ReviewReasons.VISITOR_REPORT + ": " + category.name().toLowerCase(Locale.ROOT);
        return StringUtils.isBlank(details) ? reason : reason + ": " + details;
    }

    @Override
    @Transactional
    public void resolveOpen(final Url url, final ReviewResolution resolution, final String resolvedBy) {
        final var resolvedAt = LocalDateTime.now();
        final var open = linkReviewRepository.findByUrlAndResolvedAtIsNull(url);
        open.forEach(review -> {
            review.setResolution(resolution);
            review.setResolvedBy(resolvedBy);
            review.setResolvedAt(resolvedAt);
            review.setCheckedUrl(review.getUrl().getLongUrl());
            linkReviewRepository.save(review);
            log.info("Review resolved urlId={} reviewId={} resolution={}", url.getId(), review.getId(), resolution);
        });
        if (!open.isEmpty()) {
            reviewWarningPage.clearAfterReview(url.getId());
        }
    }

    @Override
    public Page<LinkReview> open(final Pageable pageable, final String reason) {
        return open(newestFirst(pageable), reason, null, null);
    }

    @Override
    public Page<ReviewEntryResponse> openEntries(final Pageable pageable, final String reason, final Integer olderThanDays, final String search) {
        final var reviews = open(ENTRY_SORTS.apply(pageable), reason, olderThanDays, search);
        final var possibleScammers = reviews.stream().anyMatch(review -> review.getUrl().getOwner() != null)
                ? scammerSignalService.possibleScammerIds()
                : Set.<Long>of();
        final var inspections = reviews.isEmpty()
                ? Map.<Long, InspectionSummary>of()
                : linkInspectionService.summaries(reviews.stream().map(review -> review.getUrl().getId()).toList());
        return reviews.map(review -> entryOf(review, possibleScammers, inspections));
    }

    @Override
    public Set<Long> openUrlIds(final Collection<Long> urlIds) {
        if (urlIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(linkReviewRepository.findOpenUrlIds(urlIds));
    }

    private Page<LinkReview> open(final Pageable sorted, final String reason, final Integer olderThanDays, final String search) {
        final var filters = new ArrayList<Specification<LinkReview>>();
        filters.add(LinkReviewSpecification.isOpen());
        if (StringUtils.isNotBlank(reason)) {
            filters.add(LinkReviewSpecification.reasonContains(reason.trim()));
        }
        if (olderThanDays != null) {
            filters.add(LinkReviewSpecification.linkCreatedBefore(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())).minusDays(olderThanDays)));
        }
        if (StringUtils.isNotBlank(search)) {
            filters.add(LinkReviewSpecification.searchMatches(search.strip()));
        }
        return linkReviewRepository.findAll(Specification.allOf(filters), sorted);
    }

    private static ReviewEntryResponse entryOf(final LinkReview review, final Set<Long> possibleScammers, final Map<Long, InspectionSummary> inspections) {
        final var url = review.getUrl();
        final var inspection = Optional.ofNullable(inspections.get(url.getId()));
        final var owner = Optional.ofNullable(url.getOwner());
        final var host = DomainNames.hostOf(url.getLongUrl());
        return new ReviewEntryResponse(
                url.getId(),
                url.getShortUrl(),
                url.getLongUrl(),
                host.orElse(null),
                host.map(DomainNames::registrableDomain).orElse(null),
                owner.map(User::getId).orElse(null),
                owner.map(User::getEmail).orElse(null),
                owner.map(User::getCreateDate).orElse(null),
                owner.map(User::getId).filter(possibleScammers::contains).isPresent(),
                url.getCreateDate(),
                reasonsOf(review),
                review.getFlaggedAt(),
                url.getDomain() == null ? null : url.getDomain().getHostname(),
                RedirectChains.split(url.getRedirectChain()),
                inspection.map(InspectionSummary::screenshot).orElse(null),
                inspection.map(InspectionSummary::virusTotal).orElse(null),
                review.isNotice());
    }

    @Override
    public long openCount() {
        return linkReviewRepository.countByResolvedAtIsNull();
    }

    @Override
    public long openReportCount() {
        return linkReviewRepository.countByResolvedAtIsNullAndReasonsContaining(ReviewReasons.VISITOR_REPORT);
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
