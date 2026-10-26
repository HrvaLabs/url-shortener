package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.bio.BioPageReviewEntryResponse;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageReview;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioPageReviewResolution;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.repository.BioPageReviewRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AlertedBioPage;
import cc.hrva.urlshortener.service.impl.DefaultBioPageReviewService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultBioPageReviewServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-26T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final String NAME_IMITATES_PAYPAL = "name imitates paypal";
    private static final String HANDLE_IMITATES_PAYPAL = "handle imitates paypal";
    private static final String BIO_LURE = "bio has a login or verification lure";
    private static final String REPORTED_SPAM = "reported by a visitor: spam: fake shop";

    @Mock
    private BioPageReviewRepository bioPageReviewRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final User ana = User.builder().id(7L).email("ana@example.com").active(true).build();
    private final BioPage page = BioPage.builder().id(12L).owner(ana).handle("ana").displayName("Ana Horvat").build();
    private BioPageReviewService bioPageReviewService;

    @BeforeEach
    void setUp() {
        bioPageReviewService = new DefaultBioPageReviewService(bioPageReviewRepository, eventPublisher, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void aFirstFlagOpensAReviewAndAlerts() {
        final var saved = ArgumentCaptor.forClass(BioPageReview.class);

        bioPageReviewService.flag(page, List.of(HANDLE_IMITATES_PAYPAL, NAME_IMITATES_PAYPAL));

        verify(bioPageReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getPage()).isSameAs(page);
        assertThat(saved.getValue().getReasons()).isEqualTo(HANDLE_IMITATES_PAYPAL + "\n" + NAME_IMITATES_PAYPAL);
        assertThat(saved.getValue().isSystemFlagged()).isTrue();
        assertThat(saved.getValue().getFlaggedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getResolvedAt()).isNull();
        verify(eventPublisher).publishEvent(new AdminAlertEvent.BioPageReviewOpened(
                new AlertedBioPage(12L, "ana", 7L), List.of(HANDLE_IMITATES_PAYPAL, NAME_IMITATES_PAYPAL)));
    }

    @Test
    void aFlagOnAnOpenReviewExtendsItQuietly() {
        final var open = review(1L, NAME_IMITATES_PAYPAL, true);
        hasOpen(open);

        bioPageReviewService.flag(page, List.of(NAME_IMITATES_PAYPAL, BIO_LURE));

        verify(bioPageReviewRepository).save(open);
        assertThat(open.getReasons()).isEqualTo(NAME_IMITATES_PAYPAL + "\n" + BIO_LURE);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aFlagWithNothingNewWritesNothing() {
        final var open = review(1L, NAME_IMITATES_PAYPAL + "\n" + BIO_LURE, true);
        hasOpen(open);

        bioPageReviewService.flag(page, List.of(BIO_LURE, NAME_IMITATES_PAYPAL));

        verify(bioPageReviewRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aFlagOnAReviewOnlyVisitorsReportedMakesItASystemFlagQuietly() {
        final var open = review(1L, REPORTED_SPAM, false);
        hasOpen(open);

        bioPageReviewService.flag(page, List.of(NAME_IMITATES_PAYPAL));

        verify(bioPageReviewRepository).save(open);
        assertThat(open.isSystemFlagged()).isTrue();
        assertThat(open.getReasons()).isEqualTo(REPORTED_SPAM + "\n" + NAME_IMITATES_PAYPAL);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reasonsMarkedFineDoNotReopen() {
        when(bioPageReviewRepository.findFirstByPageOrderByFlaggedAtDesc(page))
                .thenReturn(Optional.of(resolved(BioPageReviewResolution.OK, HANDLE_IMITATES_PAYPAL + "\n" + NAME_IMITATES_PAYPAL)));

        bioPageReviewService.flag(page, List.of(NAME_IMITATES_PAYPAL));

        verify(bioPageReviewRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aNewReasonAfterAnOkOpensANewReview() {
        when(bioPageReviewRepository.findFirstByPageOrderByFlaggedAtDesc(page))
                .thenReturn(Optional.of(resolved(BioPageReviewResolution.OK, NAME_IMITATES_PAYPAL)));
        final var saved = ArgumentCaptor.forClass(BioPageReview.class);

        bioPageReviewService.flag(page, List.of(NAME_IMITATES_PAYPAL, BIO_LURE));

        verify(bioPageReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getReasons()).isEqualTo(NAME_IMITATES_PAYPAL + "\n" + BIO_LURE);
        verify(eventPublisher).publishEvent(any(AdminAlertEvent.BioPageReviewOpened.class));
    }

    @Test
    void theSameReasonsAfterAHideOpenANewReview() {
        when(bioPageReviewRepository.findFirstByPageOrderByFlaggedAtDesc(page))
                .thenReturn(Optional.of(resolved(BioPageReviewResolution.HIDDEN, NAME_IMITATES_PAYPAL)));

        bioPageReviewService.flag(page, List.of(NAME_IMITATES_PAYPAL));

        verify(bioPageReviewRepository).save(any(BioPageReview.class));
        verify(eventPublisher).publishEvent(any(AdminAlertEvent.BioPageReviewOpened.class));
    }

    @Test
    void aHiddenPageIsNotFlagged() {
        page.setHiddenAt(NOW);

        bioPageReviewService.flag(page, List.of(NAME_IMITATES_PAYPAL));

        verifyNoInteractions(bioPageReviewRepository, eventPublisher);
    }

    @Test
    void flaggingWithoutAReasonDoesNothing() {
        bioPageReviewService.flag(page, List.of(" ", ""));
        bioPageReviewService.flag(page, List.of());

        verifyNoInteractions(bioPageReviewRepository, eventPublisher);
    }

    @Test
    void aReasonSpanningSeveralLinesIsStoredOnOneLine() {
        final var saved = ArgumentCaptor.forClass(BioPageReview.class);

        bioPageReviewService.flag(page, List.of("name imitates\npaypal", NAME_IMITATES_PAYPAL));

        verify(bioPageReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getReasons()).isEqualTo(NAME_IMITATES_PAYPAL);
    }

    @Test
    void aFirstReportOpensAReviewThatIsNotSystemFlagged() {
        final var saved = ArgumentCaptor.forClass(BioPageReview.class);

        bioPageReviewService.report(page, ReportCategory.SPAM, "fake shop");

        verify(bioPageReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getPage()).isSameAs(page);
        assertThat(saved.getValue().getReasons()).isEqualTo(REPORTED_SPAM);
        assertThat(saved.getValue().isSystemFlagged()).isFalse();
        verify(eventPublisher).publishEvent(new AdminAlertEvent.BioPageReported(
                new AlertedBioPage(12L, "ana", 7L), ReportCategory.SPAM, "fake shop", true));
    }

    @Test
    void anImpersonationReportIsNamedInTheReviewQueue() {
        final var saved = ArgumentCaptor.forClass(BioPageReview.class);

        bioPageReviewService.report(page, ReportCategory.IMPERSONATION, "uses my photos");

        verify(bioPageReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getReasons()).isEqualTo("reported by a visitor: impersonation: uses my photos");
    }

    @Test
    void aReportWithoutDetailsNamesOnlyTheCategory() {
        final var saved = ArgumentCaptor.forClass(BioPageReview.class);

        bioPageReviewService.report(page, ReportCategory.PHISHING, null);

        verify(bioPageReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getReasons()).isEqualTo("reported by a visitor: phishing");
    }

    @Test
    void aReportOpensANewReviewEvenAfterThePageWasReviewedFine() {
        bioPageReviewService.report(page, ReportCategory.SPAM, "fake shop");

        verify(bioPageReviewRepository, never()).findFirstByPageOrderByFlaggedAtDesc(any());
        verify(bioPageReviewRepository).save(any(BioPageReview.class));
    }

    @Test
    void aReportOnAnOpenReviewAddsALineAndAlerts() {
        final var open = review(1L, NAME_IMITATES_PAYPAL, true);
        hasOpen(open);

        bioPageReviewService.report(page, ReportCategory.SPAM, "fake shop");

        verify(bioPageReviewRepository).save(open);
        assertThat(open.getReasons()).isEqualTo(NAME_IMITATES_PAYPAL + "\n" + REPORTED_SPAM);
        assertThat(open.isSystemFlagged()).isTrue();
        verify(eventPublisher).publishEvent(new AdminAlertEvent.BioPageReported(
                new AlertedBioPage(12L, "ana", 7L), ReportCategory.SPAM, "fake shop", false));
    }

    @Test
    void aRepeatOfAReportTheReviewHoldsSendsNoAlert() {
        hasOpen(review(1L, REPORTED_SPAM, false));

        bioPageReviewService.report(page, ReportCategory.SPAM, "fake shop");

        verify(bioPageReviewRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void theTwentyFirstReportIsDropped() {
        final var reasons = NAME_IMITATES_PAYPAL + "\n" + visitorReports(20);
        final var open = review(1L, reasons, true);
        hasOpen(open);

        bioPageReviewService.report(page, ReportCategory.PHISHING, "asks for my bank login");

        verify(bioPageReviewRepository, never()).save(any());
        assertThat(open.getReasons()).isEqualTo(reasons);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void theTwentiethReportIsStillTaken() {
        final var open = review(1L, visitorReports(19), false);
        hasOpen(open);

        bioPageReviewService.report(page, ReportCategory.PHISHING, "asks for my bank login");

        verify(bioPageReviewRepository).save(open);
        assertThat(open.getReasons()).endsWith("\nreported by a visitor: phishing: asks for my bank login");
    }

    @Test
    void resolveClosesEveryOpenEntry() {
        final var newer = review(2L, NAME_IMITATES_PAYPAL, true);
        final var older = review(1L, REPORTED_SPAM, false);
        when(bioPageReviewRepository.findByPageAndResolvedAtIsNull(page)).thenReturn(List.of(newer, older));

        bioPageReviewService.resolveOpen(page, BioPageReviewResolution.HIDDEN, "admin@localhost");

        verify(bioPageReviewRepository).save(newer);
        verify(bioPageReviewRepository).save(older);
        assertThat(List.of(newer, older)).allSatisfy(review -> {
            assertThat(review.getResolution()).isEqualTo(BioPageReviewResolution.HIDDEN);
            assertThat(review.getResolvedBy()).isEqualTo("admin@localhost");
            assertThat(review.getResolvedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void resolvingAPageWithoutAnOpenReviewChangesNothing() {
        when(bioPageReviewRepository.findByPageAndResolvedAtIsNull(page)).thenReturn(List.of());

        bioPageReviewService.resolveOpen(page, BioPageReviewResolution.OK, "admin@localhost");

        verify(bioPageReviewRepository, never()).save(any());
    }

    @Test
    void openEntriesAreNewestFirstWithTheOwner() {
        final var bob = User.builder().id(8L).email("bob@example.com").build();
        final var hiddenPage = BioPage.builder().id(13L).owner(bob).handle(null).displayName("Bob").hiddenAt(NOW).build();
        final var newer = review(2L, NAME_IMITATES_PAYPAL + "\n" + REPORTED_SPAM, true);
        final var older = BioPageReview.builder().id(1L).page(hiddenPage).reasons(BIO_LURE).flaggedAt(NOW.minusHours(1)).build();
        final var asked = ArgumentCaptor.forClass(Pageable.class);
        when(bioPageReviewRepository.findOpen(asked.capture())).thenReturn(new PageImpl<>(List.of(newer, older), PageRequest.of(1, 2), 5));

        final var entries = bioPageReviewService.openEntries(PageRequest.of(1, 2, Sort.by("reasons")));

        assertThat(asked.getValue().getPageNumber()).isEqualTo(1);
        assertThat(asked.getValue().getPageSize()).isEqualTo(2);
        assertThat(asked.getValue().getSort().isUnsorted()).isTrue();
        assertThat(entries.getTotalElements()).isEqualTo(5);
        assertThat(entries.getContent())
                .extracting(BioPageReviewEntryResponse::reviewId, BioPageReviewEntryResponse::pageId, BioPageReviewEntryResponse::handle,
                        BioPageReviewEntryResponse::displayName, BioPageReviewEntryResponse::ownerId, BioPageReviewEntryResponse::ownerEmail,
                        BioPageReviewEntryResponse::hidden, BioPageReviewEntryResponse::reasons, BioPageReviewEntryResponse::flaggedAt)
                .containsExactly(
                        tuple(2L, 12L, "ana", "Ana Horvat", 7L, "ana@example.com", false, List.of(NAME_IMITATES_PAYPAL, REPORTED_SPAM), NOW),
                        tuple(1L, 13L, null, "Bob", 8L, "bob@example.com", true, List.of(BIO_LURE), NOW.minusHours(1)));
    }

    @Test
    void openCountCountsTheUnresolvedReviews() {
        when(bioPageReviewRepository.countByResolvedAtIsNull()).thenReturn(3L);

        assertThat(bioPageReviewService.openCount()).isEqualTo(3L);
    }

    @Test
    void thePagesWithAnOpenReviewAreFoundInOneQuery() {
        when(bioPageReviewRepository.findOpenPageIds(List.of(12L, 13L))).thenReturn(List.of(12L));

        assertThat(bioPageReviewService.openPageIds(List.of(12L, 13L))).isEqualTo(Set.of(12L));
    }

    @Test
    void noPagesMeansNoQueryForOpenReviews() {
        assertThat(bioPageReviewService.openPageIds(List.of())).isEmpty();

        verifyNoInteractions(bioPageReviewRepository);
    }

    @Test
    void everyWriteRunsInAWritableTransaction() throws NoSuchMethodException {
        for (final var method : List.of(
                DefaultBioPageReviewService.class.getMethod("flag", BioPage.class, List.class),
                DefaultBioPageReviewService.class.getMethod("report", BioPage.class, ReportCategory.class, String.class),
                DefaultBioPageReviewService.class.getMethod("resolveOpen", BioPage.class, BioPageReviewResolution.class, String.class))) {
            final var transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).as(method.getName()).isNotNull();
            assertThat(transactional.readOnly()).as(method.getName()).isFalse();
        }
        assertThat(DefaultBioPageReviewService.class.getMethod("openPageIds", Collection.class).getAnnotation(Transactional.class)).isNull();
        assertThat(DefaultBioPageReviewService.class.getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    private void hasOpen(final BioPageReview review) {
        when(bioPageReviewRepository.findFirstByPageAndResolvedAtIsNullOrderByFlaggedAtDesc(page)).thenReturn(Optional.of(review));
    }

    private BioPageReview review(final long id, final String reasons, final boolean systemFlagged) {
        return BioPageReview.builder().id(id).page(page).reasons(reasons).systemFlagged(systemFlagged).flaggedAt(NOW).build();
    }

    private BioPageReview resolved(final BioPageReviewResolution resolution, final String reasons) {
        return BioPageReview.builder()
                .id(1L)
                .page(page)
                .reasons(reasons)
                .systemFlagged(true)
                .flaggedAt(NOW.minusDays(2))
                .resolvedAt(NOW.minusDays(1))
                .resolvedBy("admin@localhost")
                .resolution(resolution)
                .build();
    }

    private static String visitorReports(final int count) {
        return String.join("\n", IntStream.range(0, count).mapToObj(report -> "reported by a visitor: spam: report " + report).toList());
    }

}
