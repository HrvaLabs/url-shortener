package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.service.impl.DefaultReviewQueueService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultReviewQueueServiceTest {

    private static final String YOUNG = "domain registered in the last 30 days";
    private static final String LURE = "login or verification lure";
    private static final String ON_LIST = "on list Phishing.Database domains";

    @Mock
    private LinkReviewRepository linkReviewRepository;

    private ReviewQueueService reviewQueueService;

    private final Url url = Url.builder().id(287L).shortUrl("v0DB3").longUrl("https://findfavoritedate.life/?u=f6yp608").build();

    @BeforeEach
    void setUp() {
        reviewQueueService = new DefaultReviewQueueService(linkReviewRepository);
    }

    @Test
    void flaggingALinkOpensAReviewWithItsReasonsOnePerLine() {
        final var saved = ArgumentCaptor.forClass(LinkReview.class);

        reviewQueueService.flag(url, List.of(YOUNG, LURE));

        verify(linkReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getUrl()).isSameAs(url);
        assertThat(saved.getValue().getReasons()).isEqualTo(YOUNG + "\n" + LURE);
        assertThat(saved.getValue().getResolvedAt()).isNull();
    }

    @Test
    void flaggingTwiceKeepsOneOpenEntry() {
        final var open = LinkReview.builder().id(1L).url(url).reasons(YOUNG + "\n" + LURE).flaggedAt(LocalDateTime.now()).build();
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
    void openCountCountsTheUnresolvedEntries() {
        when(linkReviewRepository.countByResolvedAtIsNull()).thenReturn(4L);

        assertThat(reviewQueueService.openCount()).isEqualTo(4L);
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
