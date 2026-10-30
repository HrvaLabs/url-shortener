package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.ReviewEntryResponse;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.specification.LinkReviewSpecification;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AlertedLink;
import cc.hrva.urlshortener.service.impl.DefaultReviewQueueService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultReviewQueueServiceTest {

    private static final String YOUNG = "domain registered in the last 30 days";
    private static final String LURE = "login or verification lure";
    private static final String ON_LIST = "on list Phishing.Database domains";
    private static final String REPORTED_PHISHING = "reported by a visitor: phishing: asks for my bank login";

    @Mock
    private LinkReviewRepository linkReviewRepository;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private ScammerSignalService scammerSignalService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);

    private ReviewQueueService reviewQueueService;

    private final Url url = Url.builder().id(287L).shortUrl("v0DB3").longUrl("https://findfavoritedate.life/?u=f6yp608").active(true).build();

    @BeforeEach
    void setUp() {
        reviewQueueService = new DefaultReviewQueueService(linkReviewRepository, urlRepository, scammerSignalService, clock, eventPublisher);
        lenient().when(urlRepository.findById(287L)).thenReturn(Optional.of(url));
    }

    @Test
    void aLinkBlockedBeforeItIsQueuedGetsNoReview() {
        final var blockedMeanwhile = Url.builder().id(287L).shortUrl("v0DB3").longUrl(url.getLongUrl())
                .active(false).blockReason(BlockReason.BLOCKLIST).build();
        when(urlRepository.findById(287L)).thenReturn(Optional.of(blockedMeanwhile));

        reviewQueueService.flag(url, List.of(YOUNG, LURE));

        verifyNoInteractions(linkReviewRepository);
    }

    @Test
    void aLinkDeletedBeforeItIsQueuedGetsNoReview() {
        when(urlRepository.findById(287L)).thenReturn(Optional.empty());

        reviewQueueService.flag(url, List.of(YOUNG));

        verifyNoInteractions(linkReviewRepository);
    }

    @Test
    void flaggingALinkOpensAReviewWithItsReasonsOnePerLine() {
        final var saved = ArgumentCaptor.forClass(LinkReview.class);

        reviewQueueService.flag(url, List.of(YOUNG, LURE));

        verify(linkReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getUrl()).isSameAs(url);
        assertThat(saved.getValue().getReasons()).isEqualTo(YOUNG + "\n" + LURE);
        assertThat(saved.getValue().getResolvedAt()).isNull();
        assertThat(saved.getValue().isSystemFlagged()).isTrue();
    }

    @Test
    void flaggingTwiceKeepsOneOpenEntry() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(YOUNG + "\n" + LURE).systemFlagged(true).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.flag(url, List.of(LURE, YOUNG));

        verify(linkReviewRepository, never()).save(any());
        assertThat(open.getReasons()).isEqualTo(YOUNG + "\n" + LURE);
    }

    @Test
    void aNewReasonForAnOpenEntryIsAddedToThatEntry() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(YOUNG).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.flag(url, List.of(YOUNG, ON_LIST));

        verify(linkReviewRepository).save(open);
        assertThat(open.getReasons()).isEqualTo(YOUNG + "\n" + ON_LIST);
        assertThat(open.getId()).isEqualTo(1L);
    }

    @Test
    void aCheckFlaggingAnEntryOnlyVisitorsReportedMakesItASystemFlag() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(REPORTED_PHISHING).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.flag(url, List.of(YOUNG));

        verify(linkReviewRepository).save(open);
        assertThat(open.getReasons()).isEqualTo(REPORTED_PHISHING + "\n" + YOUNG);
        assertThat(open.isSystemFlagged()).isTrue();
    }

    @Test
    void aCheckFlaggingAnEntryWithReasonsItAlreadyHoldsStillMakesItASystemFlag() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(YOUNG).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.flag(url, List.of(YOUNG));

        verify(linkReviewRepository).save(open);
        assertThat(open.getReasons()).isEqualTo(YOUNG);
        assertThat(open.isSystemFlagged()).isTrue();
    }

    @Test
    void flaggingWithoutAReasonLeavesAnEntryOnlyVisitorsReportedAlone() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(REPORTED_PHISHING).flaggedAt(LocalDateTime.now()).build();
        lenient().when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.flag(url, List.of("  "));

        verify(linkReviewRepository, never()).save(any());
        assertThat(open.isSystemFlagged()).isFalse();
    }

    @Test
    void flaggingAfterAnOkWithTheSameReasonsDoesNothing() {
        when(linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url))
                .thenReturn(Optional.of(resolved(ReviewResolution.OK, YOUNG + "\n" + LURE)));

        reviewQueueService.flag(url, List.of(LURE, YOUNG));

        verify(linkReviewRepository, never()).save(any());
    }

    @Test
    void flaggingAfterAnOkWithFewerReasonsDoesNothing() {
        when(linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url))
                .thenReturn(Optional.of(resolved(ReviewResolution.OK, YOUNG + "\n" + LURE)));

        reviewQueueService.flag(url, List.of(YOUNG));

        verify(linkReviewRepository, never()).save(any());
    }

    @Test
    void flaggingAfterAnOkWithANewReasonOpensANewEntry() {
        final var saved = ArgumentCaptor.forClass(LinkReview.class);
        when(linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(resolved(ReviewResolution.OK, YOUNG)));

        reviewQueueService.flag(url, List.of(YOUNG, ON_LIST));

        verify(linkReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getReasons()).isEqualTo(YOUNG + "\n" + ON_LIST);
        assertThat(saved.getValue().getResolution()).isNull();
    }

    @Test
    void flaggingAfterAnotherResolutionOpensANewEntryEvenForTheSameReasons() {
        final var saved = ArgumentCaptor.forClass(LinkReview.class);
        when(linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url))
                .thenReturn(Optional.of(resolved(ReviewResolution.LINK_BLOCKED, YOUNG)));

        reviewQueueService.flag(url, List.of(YOUNG));

        verify(linkReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getReasons()).isEqualTo(YOUNG);
    }

    @Test
    void aReasonSpanningSeveralLinesIsStoredOnOneLine() {
        final var saved = ArgumentCaptor.forClass(LinkReview.class);

        reviewQueueService.flag(url, List.of("reported by a visitor: OTHER: first line\nsecond line", YOUNG));

        verify(linkReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getReasons()).isEqualTo("reported by a visitor: OTHER: first line second line\n" + YOUNG);
    }

    @Test
    void resolvingRecordsWhoResolvedTheOpenEntryHowAndWhen() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(YOUNG).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findByUrlAndResolvedAtIsNull(url)).thenReturn(List.of(open));

        reviewQueueService.resolveOpen(url, ReviewResolution.DOMAIN_TRUSTED, "admin@localhost");

        verify(linkReviewRepository).save(open);
        assertThat(open.getResolution()).isEqualTo(ReviewResolution.DOMAIN_TRUSTED);
        assertThat(open.getResolvedBy()).isEqualTo("admin@localhost");
        assertThat(open.getResolvedAt()).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void resolvingClosesEveryOpenEntryOfTheLinkEvenADuplicate() {
        final var newer = LinkReview.builder().id(2L).url(url).reasons(LURE).flaggedAt(LocalDateTime.now()).build();
        final var older = LinkReview.builder().id(1L).url(url).reasons(YOUNG).flaggedAt(LocalDateTime.now().minusMinutes(1)).build();
        when(linkReviewRepository.findByUrlAndResolvedAtIsNull(url)).thenReturn(List.of(newer, older));

        reviewQueueService.resolveOpen(url, ReviewResolution.DESTINATION_CHANGED, "system");

        verify(linkReviewRepository).save(newer);
        verify(linkReviewRepository).save(older);
        assertThat(List.of(newer, older)).allSatisfy(review -> {
            assertThat(review.getResolution()).isEqualTo(ReviewResolution.DESTINATION_CHANGED);
            assertThat(review.getResolvedBy()).isEqualTo("system");
            assertThat(review.getResolvedAt()).isNotNull();
        });
    }

    @Test
    void flaggingAfterADestinationChangeOpensANewEntryEvenForTheSameReasons() {
        final var saved = ArgumentCaptor.forClass(LinkReview.class);
        when(linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url))
                .thenReturn(Optional.of(resolved(ReviewResolution.DESTINATION_CHANGED, YOUNG)));

        reviewQueueService.flag(url, List.of(YOUNG));

        verify(linkReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getReasons()).isEqualTo(YOUNG);
    }

    @Test
    void resolvingALinkWithoutAnOpenEntryChangesNothing() {
        reviewQueueService.resolveOpen(url, ReviewResolution.OK, "admin@localhost");

        verify(linkReviewRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOpenEntriesComeNewestFirstWhateverSortIsAsked() {
        final var sent = ArgumentCaptor.forClass(Pageable.class);
        when(linkReviewRepository.findAll(any(Specification.class), sent.capture())).thenReturn(new PageImpl<>(List.of()));

        reviewQueueService.open(PageRequest.of(2, 10, Sort.by("reasons")), "young");

        assertThat(sent.getValue().getPageNumber()).isEqualTo(2);
        assertThat(sent.getValue().getPageSize()).isEqualTo(10);
        assertThat(sent.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("flaggedAt"), Sort.Order.desc("id")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aQueueEntryCarriesTheLinkItsOwnerAndEachReasonOnItsOwnLine() {
        final var owner = User.builder().id(27L).email("rhea@gmail.com").createDate(LocalDateTime.of(2026, 10, 19, 12, 0)).build();
        final var link = Url.builder().id(287L).shortUrl("v0DB3").longUrl("https://findfavoritedate.life/?u=f6yp608").owner(owner)
                .createDate(LocalDateTime.of(2026, 10, 20, 7, 15)).build();
        final var review = LinkReview.builder().id(5L).url(link).reasons(YOUNG + "\n " + LURE + " \n\n").flaggedAt(LocalDateTime.of(2026, 10, 20, 7, 16)).build();
        when(linkReviewRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(review)));
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of(27L));

        final var entries = reviewQueueService.openEntries(PageRequest.of(0, 20), null, null);

        assertThat(entries.getContent()).containsExactly(new ReviewEntryResponse(287L, "v0DB3", "https://findfavoritedate.life/?u=f6yp608",
                "findfavoritedate.life", "findfavoritedate.life", 27L, "rhea@gmail.com", LocalDateTime.of(2026, 10, 19, 12, 0), true, LocalDateTime.of(2026, 10, 20, 7, 15), List.of(YOUNG, LURE),
                LocalDateTime.of(2026, 10, 20, 7, 16), null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aQueueEntryOnADomainCarriesTheHostname() {
        final var link = Url.builder().id(287L).shortUrl("menu").longUrl("https://findfavoritedate.life/?u=f6yp608")
                .domain(CustomDomain.builder().id(7L).hostname("go.example.com").build()).build();
        final var review = LinkReview.builder().id(5L).url(link).reasons(YOUNG).flaggedAt(LocalDateTime.of(2026, 10, 20, 7, 16)).build();
        when(linkReviewRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(review)));

        final var entries = reviewQueueService.openEntries(PageRequest.of(0, 20), null, null);

        assertThat(entries.getContent()).extracting(ReviewEntryResponse::domain).containsExactly("go.example.com");
        assertThat(JsonMapper.builder().build().writeValueAsString(entries.getContent().getFirst())).contains("\"domain\":\"go.example.com\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aQueueEntryNamesTheHostAndTheRegistrableDomainTheDomainActionsWouldUse() {
        final var subdomain = Url.builder().id(290L).shortUrl("bank").longUrl("https://Login.Secure-Bank.co.uk:443/verify").build();
        final var unreadable = Url.builder().id(291L).shortUrl("odd").longUrl("https://exa mple.com/").build();
        when(linkReviewRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                LinkReview.builder().id(8L).url(subdomain).reasons(LURE).build(),
                LinkReview.builder().id(9L).url(unreadable).reasons(LURE).build())));

        final var entries = reviewQueueService.openEntries(PageRequest.of(0, 20), null, null);

        assertThat(entries.getContent())
                .extracting(ReviewEntryResponse::host, ReviewEntryResponse::registrableDomain)
                .containsExactly(tuple("login.secure-bank.co.uk", "secure-bank.co.uk"), tuple(null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void possibleScammersAreLookedUpOnceForTheWholePage() {
        final var scammer = User.builder().id(27L).email("rhea@gmail.com").build();
        final var honest = User.builder().id(28L).email("ana@example.com").build();
        final var reviews = List.of(openReview(1L, scammer), openReview(2L, honest), openReview(3L, scammer), openReview(4L, null));
        when(linkReviewRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(reviews));
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of(27L));

        final var entries = reviewQueueService.openEntries(PageRequest.of(0, 20), null, null);

        assertThat(entries.getContent()).extracting(ReviewEntryResponse::possibleScammer).containsExactly(true, false, true, false);
        verify(scammerSignalService, Mockito.times(1)).possibleScammerIds();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAnonymousEntryHasNoOwnerAndAPageWithoutOwnersAsksNothingAboutScammers() {
        when(linkReviewRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(openReview(4L, null))));

        final var entry = reviewQueueService.openEntries(PageRequest.of(0, 1), null, null).getContent().getFirst();

        assertThat(entry.ownerId()).isNull();
        assertThat(entry.ownerEmail()).isNull();
        assertThat(entry.ownerCreatedAt()).isNull();
        assertThat(entry.possibleScammer()).isFalse();
        verifyNoInteractions(scammerSignalService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queueEntriesComeNewestFirstWhateverSortIsAsked() {
        final var sent = ArgumentCaptor.forClass(Pageable.class);
        when(linkReviewRepository.findAll(any(Specification.class), sent.capture())).thenReturn(new PageImpl<>(List.of()));

        reviewQueueService.openEntries(PageRequest.of(1, 5, Sort.by("reasons")), null, null);

        assertThat(sent.getValue().getPageNumber()).isEqualTo(1);
        assertThat(sent.getValue().getPageSize()).isEqualTo(5);
        assertThat(sent.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("flaggedAt"), Sort.Order.desc("id")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAgeFilterKeepsLinksCreatedMoreThanThatManyDaysAgoAlongsideTheReasonFilter() {
        when(linkReviewRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        final var cutoff = LocalDateTime.now(clock.withZone(ZoneId.systemDefault())).minusDays(7);

        try (final var specifications = mockStatic(LinkReviewSpecification.class, Mockito.CALLS_REAL_METHODS)) {
            reviewQueueService.openEntries(PageRequest.of(0, 20), " young ", 7);

            specifications.verify(LinkReviewSpecification::isOpen);
            specifications.verify(() -> LinkReviewSpecification.reasonContains("young"));
            specifications.verify(() -> LinkReviewSpecification.linkCreatedBefore(cutoff));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAnAgeOrReasonOnlyOpenEntriesAreAsked() {
        when(linkReviewRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        try (final var specifications = mockStatic(LinkReviewSpecification.class, Mockito.CALLS_REAL_METHODS)) {
            reviewQueueService.openEntries(PageRequest.of(0, 20), " ", null);

            specifications.verify(LinkReviewSpecification::isOpen);
            specifications.verify(() -> LinkReviewSpecification.reasonContains(anyString()), never());
            specifications.verify(() -> LinkReviewSpecification.linkCreatedBefore(any()), never());
        }
    }

    @Test
    void theLinksWithAnOpenReviewAreFoundInOneQuery() {
        when(linkReviewRepository.findOpenUrlIds(List.of(287L, 288L, 289L))).thenReturn(List.of(288L, 288L));

        assertThat(reviewQueueService.openUrlIds(List.of(287L, 288L, 289L))).containsExactly(288L);
    }

    @Test
    void noLinksMeansNoQueryForOpenReviews() {
        assertThat(reviewQueueService.openUrlIds(List.of())).isEmpty();

        verifyNoInteractions(linkReviewRepository);
    }

    @Test
    void openCountCountsTheUnresolvedEntries() {
        when(linkReviewRepository.countByResolvedAtIsNull()).thenReturn(4L);

        assertThat(reviewQueueService.openCount()).isEqualTo(4L);
    }

    @Test
    void aVisitorReportOpensAReviewWithTheCategoryAndDetails() {
        final var saved = ArgumentCaptor.forClass(LinkReview.class);

        reviewQueueService.report(url, ReportCategory.PHISHING, "asks for my bank login");

        verify(linkReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getUrl()).isSameAs(url);
        assertThat(saved.getValue().getReasons()).isEqualTo(REPORTED_PHISHING);
        assertThat(saved.getValue().getResolvedAt()).isNull();
        assertThat(saved.getValue().isSystemFlagged()).isFalse();
    }

    @Test
    void aReportWithoutDetailsNamesOnlyTheCategory() {
        final var saved = ArgumentCaptor.forClass(LinkReview.class);

        reviewQueueService.report(url, ReportCategory.SPAM, null);

        verify(linkReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getReasons()).isEqualTo("reported by a visitor: spam");
    }

    @Test
    void blankDetailsAreLeftOutOfTheReason() {
        final var saved = ArgumentCaptor.forClass(LinkReview.class);

        reviewQueueService.report(url, ReportCategory.OTHER, "   ");

        verify(linkReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getReasons()).isEqualTo("reported by a visitor: other");
    }

    @Test
    void aSecondReportAddsItsReasonToTheOpenReviewInsteadOfOpeningAnother() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(REPORTED_PHISHING).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.report(url, ReportCategory.MALWARE, "downloads an exe");

        verify(linkReviewRepository).save(open);
        assertThat(open.getId()).isEqualTo(1L);
        assertThat(open.getReasons()).isEqualTo(REPORTED_PHISHING + "\nreported by a visitor: malware: downloads an exe");
        assertThat(open.isSystemFlagged()).isFalse();
    }

    @Test
    void aReportExtendsAnOpenReviewTheChecksOpenedAndKeepsItASystemFlag() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(YOUNG).systemFlagged(true).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.report(url, ReportCategory.PHISHING, "asks for my bank login");

        verify(linkReviewRepository).save(open);
        assertThat(open.getReasons()).isEqualTo(YOUNG + "\n" + REPORTED_PHISHING);
        assertThat(open.isSystemFlagged()).isTrue();
    }

    @Test
    void aReportOpensANewEntryEvenAfterTheLinkWasReviewedOkForTheSameReason() {
        final var saved = ArgumentCaptor.forClass(LinkReview.class);
        lenient().when(linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url))
                .thenReturn(Optional.of(resolved(ReviewResolution.OK, REPORTED_PHISHING)));

        reviewQueueService.report(url, ReportCategory.PHISHING, "asks for my bank login");

        verify(linkReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getReasons()).isEqualTo(REPORTED_PHISHING);
        assertThat(saved.getValue().getResolution()).isNull();
    }

    @Test
    void anOpenReviewHoldingTwentyVisitorReportsTakesNoMore() {
        final var reasons = YOUNG + "\n" + visitorReports(20);
        final var open = LinkReview.builder().id(1L).url(url).reasons(reasons).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.report(url, ReportCategory.PHISHING, "asks for my bank login");

        verify(linkReviewRepository, never()).save(any());
        assertThat(open.getReasons()).isEqualTo(reasons);
    }

    @Test
    void anOpenReviewWithNineteenVisitorReportsTakesOneMoreWhateverElseItHolds() {
        final var reasons = YOUNG + "\n" + ON_LIST + "\n" + visitorReports(19);
        final var open = LinkReview.builder().id(1L).url(url).reasons(reasons).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.report(url, ReportCategory.PHISHING, "asks for my bank login");

        verify(linkReviewRepository).save(open);
        assertThat(open.getReasons()).isEqualTo(reasons + "\n" + REPORTED_PHISHING);
    }

    @Test
    void aReportNeverChangesTheLink() {
        reviewQueueService.report(url, ReportCategory.PHISHING, "asks for my bank login");

        assertThat(url.isActive()).isTrue();
        assertThat(url.getBlockReason()).isNull();
        assertThat(url.getBlockDetail()).isNull();
        assertThat(url.getBlockedAt()).isNull();
    }

    @Test
    void aReportRunsInAWritableTransaction() throws NoSuchMethodException {
        final var transactional = DefaultReviewQueueService.class
                .getMethod("report", Url.class, ReportCategory.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isFalse();
    }

    @Test
    void openReportCountCountsTheOpenEntriesAVisitorReported() {
        when(linkReviewRepository.countByResolvedAtIsNullAndReasonsContaining("reported by a visitor")).thenReturn(2L);

        assertThat(reviewQueueService.openReportCount()).isEqualTo(2L);
    }

    @Test
    void aNewlyOpenedReviewAlertsTheAdminWithTheDestinationHostOnly() {
        reviewQueueService.flag(url, List.of(YOUNG, LURE));

        verify(eventPublisher).publishEvent(new AdminAlertEvent.ReviewOpened(
                new AlertedLink(287L, "v0DB3", "findfavoritedate.life", null, null), List.of(YOUNG, LURE)));
    }

    @Test
    void anAlertNamesTheOwnerByIdNeverByEmail() {
        final var owned = Url.builder().id(288L).shortUrl("k3Xa1").longUrl("https://paypa1-login.top/verify")
                .owner(User.builder().id(27L).email("scammer@example.com").build()).active(true).build();
        when(urlRepository.findById(288L)).thenReturn(Optional.of(owned));

        reviewQueueService.flag(owned, List.of(LURE));

        verify(eventPublisher).publishEvent(new AdminAlertEvent.ReviewOpened(
                new AlertedLink(288L, "k3Xa1", "paypa1-login.top", 27L, null), List.of(LURE)));
    }

    @Test
    void newReasonsOnAnOpenReviewSendNoAlert() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(YOUNG).systemFlagged(true).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.flag(url, List.of(YOUNG, ON_LIST));

        verify(linkReviewRepository).save(open);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reasonsAlreadyMarkedFineSendNoAlert() {
        when(linkReviewRepository.findFirstByUrlOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(resolved(ReviewResolution.OK, YOUNG + "\n" + LURE)));

        reviewQueueService.flag(url, List.of(YOUNG));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aVisitorReportThatOpensAReviewAlertsTheAdmin() {
        reviewQueueService.report(url, ReportCategory.PHISHING, "asks for my bank login");

        verify(eventPublisher).publishEvent(new AdminAlertEvent.VisitorReported(
                new AlertedLink(287L, "v0DB3", "findfavoritedate.life", null, null), ReportCategory.PHISHING, "asks for my bank login", true));
    }

    @Test
    void aVisitorReportOnAnOpenReviewAlertsTheAdmin() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(YOUNG).systemFlagged(true).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.report(url, ReportCategory.MALWARE, "downloads an exe");

        verify(eventPublisher).publishEvent(new AdminAlertEvent.VisitorReported(
                new AlertedLink(287L, "v0DB3", "findfavoritedate.life", null, null), ReportCategory.MALWARE, "downloads an exe", false));
    }

    @Test
    void aRepeatOfAReportTheReviewAlreadyHoldsSendsNoAlert() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(REPORTED_PHISHING).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.report(url, ReportCategory.PHISHING, "asks for my bank login");

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aReportOnAReviewFullOfVisitorReportsSendsNoAlert() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(visitorReports(20)).flaggedAt(LocalDateTime.now()).build();
        when(linkReviewRepository.findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(url)).thenReturn(Optional.of(open));

        reviewQueueService.report(url, ReportCategory.PHISHING, "asks for my bank login");

        verifyNoInteractions(eventPublisher);
    }

    private static String visitorReports(final int count) {
        return String.join("\n", IntStream.range(0, count).mapToObj(report -> "reported by a visitor: spam: report " + report).toList());
    }

    private static LinkReview openReview(final long id, final User owner) {
        final var link = Url.builder().id(id).shortUrl("s" + id).longUrl("https://example.org/" + id).owner(owner).createDate(LocalDateTime.now()).build();
        return LinkReview.builder().id(id).url(link).reasons(LURE).flaggedAt(LocalDateTime.now()).build();
    }

    private LinkReview resolved(final ReviewResolution resolution, final String reasons) {
        return LinkReview.builder()
                .id(1L)
                .url(url)
                .reasons(reasons)
                .flaggedAt(LocalDateTime.now().minusDays(2))
                .resolvedAt(LocalDateTime.now().minusDays(1))
                .resolvedBy("admin@localhost")
                .resolution(resolution)
                .build();
    }

}
