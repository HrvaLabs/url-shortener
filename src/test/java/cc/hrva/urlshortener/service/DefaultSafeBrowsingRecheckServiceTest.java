package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ThreatType;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultSafeBrowsingRecheckService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSafeBrowsingRecheckServiceTest {

    private static final String SAFE_BROWSING_DOWN = "URL safety check is temporarily unavailable. Please try again later.";
    private static final List<String> YOUNG_LOOKALIKE = List.of("domain registered in the last 30 days", "imitates njuskalo");

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private SafeBrowsingService safeBrowsingService;

    @Mock
    private LinkCheckService linkCheckService;

    @Mock
    private ModerationService moderationService;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private RedirectChainService redirectChainService;

    private SafeBrowsingRecheckService recheckService;

    private final Map<Long, Url> database = new TreeMap<>();
    private final List<Long> stamped = new ArrayList<>();

    @BeforeEach
    void setUp() {
        recheckService = new DefaultSafeBrowsingRecheckService(urlRepository, safeBrowsingService, linkCheckService, moderationService,
                reviewQueueService, redirectChainService);
        LongStream.rangeClosed(1, 45).forEach(id -> database.put(id,
                Url.builder().id(id).shortUrl("s" + id).longUrl("https://site" + id + ".example").active(true).build()));
        lenient().when(urlRepository.findActiveIds()).thenAnswer(invocation -> new ArrayList<>(database.keySet()));
        lenient().when(urlRepository.findAllById(anyList())).thenAnswer(invocation -> {
            final List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(database::get).map(DefaultSafeBrowsingRecheckServiceTest::loaded).toList();
        });
        lenient().when(urlRepository.markSafeBrowsingChecked(anyCollection(), any(LocalDateTime.class))).thenAnswer(invocation -> {
            final Collection<Long> ids = invocation.getArgument(0);
            final LocalDateTime checkedAt = invocation.getArgument(1);
            ids.forEach(id -> database.get(id).setLastSafeBrowsingCheck(checkedAt));
            stamped.addAll(ids);
            return ids.size();
        });
        lenient().when(urlRepository.saveAll(anyIterable())).thenAnswer(invocation -> {
            final Iterable<Url> urls = invocation.getArgument(0);
            urls.forEach(url -> database.put(url.getId(), url));
            return urls;
        });
        lenient().when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> {
            final Url url = invocation.getArgument(0);
            database.put(url.getId(), url);
            return url;
        });
        lenient().doAnswer(invocation -> {
            final var stored = database.get(invocation.<Long>getArgument(0));
            stored.setActive(false);
            stored.setBlockReason(invocation.getArgument(1));
            stored.setBlockDetail(invocation.getArgument(2));
            stored.setBlockedAt(LocalDateTime.now());
            if (stored.getBlockReason() == BlockReason.SAFE_BROWSING) {
                stored.setThreatType(ThreatType.MALWARE);
            }
            return null;
        }).when(moderationService).blockLink(anyLong(), any(BlockReason.class), any(), anyString(), anyString());
        lenient().when(safeBrowsingService.checkUrlsForThreats(anyString())).thenReturn(List.of());
        lenient().when(linkCheckService.check(anyString())).thenReturn(LinkVerdict.clean());
        lenient().when(redirectChainService.check(anyLong())).thenReturn(RedirectChainService.Result.CHECKED);
    }

    @Test
    void scansEveryActiveLinkEvenWhenEarlierOnesGetBlocked() {
        threat(1L);
        threat(2L);
        verdict(3L, blocked(BlockReason.BLOCKLIST, "URLhaus"));

        recheckService.recheckActiveUrls();

        assertThat(stamped).containsExactlyInAnyOrderElementsOf(database.keySet());
        assertThat(database.values()).allSatisfy(url -> assertThat(url.getLastSafeBrowsingCheck()).isNotNull());
        assertThat(database.values().stream().filter(url -> !url.isActive()).map(Url::getShortUrl)).containsExactly("s1", "s2", "s3");
    }

    @Test
    void aSafeBrowsingHitBlocksTheLinkAsMalwareThroughModeration() {
        threat(7L);

        recheckService.recheckActiveUrls();

        verify(moderationService).blockLink(7L, BlockReason.SAFE_BROWSING, "https://site7.example", "system", "nightly");
        verify(moderationService, times(1)).blockLink(anyLong(), any(BlockReason.class), any(), anyString(), anyString());
        assertThat(database.get(7L).isActive()).isFalse();
        assertThat(database.get(7L).getBlockReason()).isEqualTo(BlockReason.SAFE_BROWSING);
        assertThat(database.get(7L).getThreatType()).isEqualTo(ThreatType.MALWARE);
        verify(reviewQueueService, never()).flag(any(), anyList());
    }

    @Test
    void aSafeBrowsingDetailNamesEachThreatOnceAndFitsTheColumn() {
        final var longUrl = "https://site14.example/" + "a".repeat(300);
        database.get(14L).setLongUrl(longUrl);
        when(safeBrowsingService.checkUrlsForThreats(longUrl)).thenReturn(List.of(longUrl));
        when(safeBrowsingService.checkUrlsForThreats("https://site15.example")).thenReturn(List.of("https://site15.example", "https://site15.example"));

        recheckService.recheckActiveUrls();

        verify(moderationService).blockLink(14L, BlockReason.SAFE_BROWSING, longUrl.substring(0, 255), "system", "nightly");
        verify(moderationService).blockLink(15L, BlockReason.SAFE_BROWSING, "https://site15.example", "system", "nightly");
    }

    @Test
    void aBlockFromAListADomainRuleOrSpamhausBlocksTheLinkWithTheVerdictsReason() {
        verdict(3L, blocked(BlockReason.BLOCKLIST, "URLhaus"));
        verdict(4L, blocked(BlockReason.DOMAIN_RULE, "jofogas.cards"));
        verdict(5L, blocked(BlockReason.BLOCKLIST, "Spamhaus DBL: phish"));

        recheckService.recheckActiveUrls();

        verify(moderationService).blockLink(3L, BlockReason.BLOCKLIST, "URLhaus", "system", "nightly");
        verify(moderationService).blockLink(4L, BlockReason.DOMAIN_RULE, "jofogas.cards", "system", "nightly");
        verify(moderationService).blockLink(5L, BlockReason.BLOCKLIST, "Spamhaus DBL: phish", "system", "nightly");
        verify(moderationService, times(3)).blockLink(anyLong(), any(BlockReason.class), any(), anyString(), anyString());
        verify(reviewQueueService, never()).flag(any(), anyList());
    }

    @Test
    void scamSignalsOnAnExistingLinkQueueItForReviewInsteadOfBlockingIt() {
        verdict(9L, new LinkVerdict(Outcome.BLOCK, YOUNG_LOOKALIKE, BlockReason.SCAM_SIGNALS,
                "domain registered in the last 30 days, imitates njuskalo"));

        recheckService.recheckActiveUrls();

        verify(reviewQueueService).flag(linkWithId(9L), eq(YOUNG_LOOKALIKE));
        verify(moderationService, never()).blockLink(anyLong(), any(BlockReason.class), any(), anyString(), anyString());
        assertThat(database.get(9L).isActive()).isTrue();
    }

    @Test
    void aFlagVerdictQueuesTheLinkForReview() {
        final var reasons = List.of("on list Phishing.Database domains");
        verdict(11L, new LinkVerdict(Outcome.FLAG, reasons, null, null));

        recheckService.recheckActiveUrls();

        verify(reviewQueueService).flag(linkWithId(11L), eq(reasons));
        verify(reviewQueueService, times(1)).flag(any(), anyList());
        verify(moderationService, never()).blockLink(anyLong(), any(BlockReason.class), any(), anyString(), anyString());
    }

    @Test
    void anAllowListedLinkIsStillCheckedWithSafeBrowsing() {
        final var allowed = new LinkVerdict(Outcome.ALLOWED, List.of("allowed domain docs.google.com"), null, null);
        lenient().when(linkCheckService.check("https://site12.example")).thenReturn(allowed);
        verdict(16L, allowed);
        threat(12L);

        recheckService.recheckActiveUrls();

        verify(safeBrowsingService).checkUrlsForThreats("https://site16.example");
        verify(moderationService).blockLink(12L, BlockReason.SAFE_BROWSING, "https://site12.example", "system", "nightly");
        verify(moderationService, times(1)).blockLink(anyLong(), any(BlockReason.class), any(), anyString(), anyString());
        assertThat(database.get(16L).isActive()).isTrue();
    }

    @Test
    void aSafeBrowsingHitWinsOverAnyVerdictSoNoAllowRuleCanRestoreTheLink() {
        lenient().when(linkCheckService.check("https://site13.example"))
                .thenReturn(new LinkVerdict(Outcome.FLAG, List.of("on list HaGeZi DynDNS"), null, null));
        lenient().when(linkCheckService.check("https://site17.example")).thenReturn(blocked(BlockReason.BLOCKLIST, "HaGeZi TIF medium"));
        threat(13L);
        threat(17L);

        recheckService.recheckActiveUrls();

        verify(moderationService).blockLink(13L, BlockReason.SAFE_BROWSING, "https://site13.example", "system", "nightly");
        verify(moderationService).blockLink(17L, BlockReason.SAFE_BROWSING, "https://site17.example", "system", "nightly");
        verify(moderationService, never()).blockLink(anyLong(), eq(BlockReason.BLOCKLIST), any(), anyString(), anyString());
        verify(reviewQueueService, never()).flag(any(), anyList());
        assertThat(List.of(13L, 17L)).allSatisfy(id -> assertThat(database.get(id).getThreatType()).isEqualTo(ThreatType.MALWARE));
    }

    @Test
    void aLinkBlockedDuringTheScanIsNeverSavedBackAsActive() {
        threat(7L);
        verdict(8L, blocked(BlockReason.BLOCKLIST, "OpenPhish"));
        verdict(30L, blocked(BlockReason.DOMAIN_RULE, "jofogas.cards"));

        recheckService.recheckActiveUrls();

        assertThat(List.of(7L, 8L, 30L)).allSatisfy(id -> {
            assertThat(database.get(id).isActive()).isFalse();
            assertThat(database.get(id).getBlockReason()).isNotNull();
            assertThat(database.get(id).getLastSafeBrowsingCheck()).isNotNull();
        });
        verify(urlRepository, never()).saveAll(anyIterable());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void stampsEachChunkOnceWithTheLinksItScanned() {
        recheckService.recheckActiveUrls();

        verify(urlRepository, times(3)).markSafeBrowsingChecked(anyCollection(), any(LocalDateTime.class));
        assertThat(stamped).hasSize(45).doesNotHaveDuplicates();
    }

    @Test
    void skipsALinkThatWasDeactivatedWhileTheScanRan() {
        database.get(3L).setActive(false);

        recheckService.recheckActiveUrls();

        verify(safeBrowsingService, never()).checkUrlsForThreats("https://site3.example");
        verify(linkCheckService, never()).check("https://site3.example");
        assertThat(stamped).doesNotContain(3L);
    }

    @Test
    void aLinkThatFailsToScanDoesNotStopTheRest() {
        threat(2L);
        doAnswer(invocation -> {
            throw new UrlNotFoundException("Url doesn't exist");
        }).when(moderationService).blockLink(eq(2L), any(BlockReason.class), any(), anyString(), anyString());
        when(linkCheckService.check("https://site5.example")).thenThrow(new IllegalStateException("RDAP exploded"));

        recheckService.recheckActiveUrls();

        assertThat(stamped).hasSize(43).doesNotContain(2L, 5L);
        verify(linkCheckService).check("https://site45.example");
    }

    @Test
    void whenSafeBrowsingIsDownTheListsStillApplyToEveryLinkAndNothingIsStampedAsChecked() {
        final var reasons = List.of("on list Phishing.Database domains");
        when(safeBrowsingService.checkUrlsForThreats(anyString())).thenThrow(new UrlValidationException(SAFE_BROWSING_DOWN));
        verdict(3L, blocked(BlockReason.BLOCKLIST, "URLhaus"));
        verdict(11L, new LinkVerdict(Outcome.FLAG, reasons, null, null));
        verdict(44L, blocked(BlockReason.DOMAIN_RULE, "jofogas.cards"));

        recheckService.recheckActiveUrls();

        verify(safeBrowsingService, times(1)).checkUrlsForThreats(anyString());
        verify(linkCheckService, times(45)).check(anyString());
        verify(moderationService).blockLink(3L, BlockReason.BLOCKLIST, "URLhaus", "system", "nightly");
        verify(moderationService).blockLink(44L, BlockReason.DOMAIN_RULE, "jofogas.cards", "system", "nightly");
        verify(moderationService, times(2)).blockLink(anyLong(), any(BlockReason.class), any(), anyString(), anyString());
        verify(reviewQueueService).flag(linkWithId(11L), eq(reasons));
        assertThat(stamped).isEmpty();
        assertThat(database.values()).allSatisfy(url -> assertThat(url.getLastSafeBrowsingCheck()).isNull());
    }

    @Test
    void safeBrowsingGoingDownMidScanStampsOnlyTheLinksItReallyChecked() {
        when(safeBrowsingService.checkUrlsForThreats("https://site5.example")).thenThrow(new UrlValidationException(SAFE_BROWSING_DOWN));

        recheckService.recheckActiveUrls();

        verify(safeBrowsingService, times(5)).checkUrlsForThreats(anyString());
        verify(linkCheckService, times(45)).check(anyString());
        assertThat(stamped).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void afterTheScanFollowsTheRedirectsOfUpToFiveHundredLinksNotCheckedForAWeekOldestFirst() {
        when(urlRepository.findIdsDueForRedirectCheck(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(12L, 3L, 40L));
        final var earliest = LocalDateTime.now().minusDays(7);

        recheckService.recheckActiveUrls();

        final var checkedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        final var page = ArgumentCaptor.forClass(Pageable.class);
        final var order = inOrder(urlRepository, redirectChainService);
        order.verify(urlRepository, times(3)).markSafeBrowsingChecked(anyCollection(), any(LocalDateTime.class));
        order.verify(urlRepository).findIdsDueForRedirectCheck(checkedBefore.capture(), page.capture());
        order.verify(redirectChainService).check(12L);
        order.verify(redirectChainService).check(3L);
        order.verify(redirectChainService).check(40L);
        assertThat(checkedBefore.getValue()).isBetween(earliest, LocalDateTime.now().minusDays(7));
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(500);
        verify(urlRepository, never()).saveAll(anyIterable());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void aRedirectCheckThatFailsDoesNotStopTheRest() {
        when(urlRepository.findIdsDueForRedirectCheck(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("database unavailable")).when(redirectChainService).check(1L);

        recheckService.recheckActiveUrls();

        verify(redirectChainService).check(2L);
    }

    @Test
    void whenSafeBrowsingFailedDuringTheScanTheRedirectChecksWaitForTheNextNight() {
        when(safeBrowsingService.checkUrlsForThreats("https://site5.example")).thenThrow(new UrlValidationException(SAFE_BROWSING_DOWN));

        recheckService.recheckActiveUrls();

        verify(urlRepository, never()).findIdsDueForRedirectCheck(any(LocalDateTime.class), any(Pageable.class));
        verifyNoInteractions(redirectChainService);
    }

    @Test
    void theRedirectChecksStopAtTheFirstSafeBrowsingFailure() {
        when(urlRepository.findIdsDueForRedirectCheck(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(1L, 2L, 3L));
        when(redirectChainService.check(2L)).thenReturn(RedirectChainService.Result.SAFE_BROWSING_UNAVAILABLE);

        recheckService.recheckActiveUrls();

        verify(redirectChainService).check(1L);
        verify(redirectChainService).check(2L);
        verify(redirectChainService, never()).check(3L);
    }

    @Test
    void theScanRunsOutsideAnyTransactionSoEachBlockAndFlagCommitsOnItsOwn() throws NoSuchMethodException {
        final var transactional = DefaultSafeBrowsingRecheckService.class.getMethod("recheckActiveUrls").getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    private void threat(final long id) {
        final var longUrl = database.get(id).getLongUrl();
        when(safeBrowsingService.checkUrlsForThreats(longUrl)).thenReturn(List.of(longUrl));
    }

    private void verdict(final long id, final LinkVerdict verdict) {
        when(linkCheckService.check(database.get(id).getLongUrl())).thenReturn(verdict);
    }

    private static LinkVerdict blocked(final BlockReason reason, final String detail) {
        return new LinkVerdict(Outcome.BLOCK, List.of("on list " + detail), reason, detail);
    }

    private static Url linkWithId(final long id) {
        return argThat(url -> url != null && url.getId() == id);
    }

    private static Url loaded(final Url stored) {
        return Url.builder()
                .id(stored.getId())
                .shortUrl(stored.getShortUrl())
                .longUrl(stored.getLongUrl())
                .owner(stored.getOwner())
                .active(stored.isActive())
                .threatType(stored.getThreatType())
                .blockReason(stored.getBlockReason())
                .blockDetail(stored.getBlockDetail())
                .blockedAt(stored.getBlockedAt())
                .lastSafeBrowsingCheck(stored.getLastSafeBrowsingCheck())
                .build();
    }

}
