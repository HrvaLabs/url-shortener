package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.RedirectFollower;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.RedirectChainService.Result;
import cc.hrva.urlshortener.service.impl.DefaultRedirectChainService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultRedirectChainServiceTest {

    private static final long LINK_ID = 7L;
    private static final String DESTINATION = "https://innocent.example/go";
    private static final String TRACKER = "https://track.example/c?id=1";
    private static final String PHISHING = "https://njuskaio.cam/a/MAUB3";
    private static final Instant NOW = Instant.parse("2026-10-21T02:30:00Z");
    private static final LocalDateTime CHECKED_AT = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
    private static final List<String> YOUNG_LOOKALIKE = List.of("domain registered in the last 30 days", "imitates njuskalo");

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private RedirectFollower redirectFollower;

    @Mock
    private LinkCheckService linkCheckService;

    @Mock
    private SafeBrowsingService safeBrowsingService;

    @Mock
    private ModerationService moderationService;

    @Mock
    private ReviewQueueService reviewQueueService;

    private RedirectChainService redirectChainService;

    private final Url link = Url.builder().id(LINK_ID).shortUrl("abc").longUrl(DESTINATION).active(true).build();

    @BeforeEach
    void setUp() {
        redirectChainService = new DefaultRedirectChainService(urlRepository, redirectFollower, linkCheckService, safeBrowsingService,
                moderationService, reviewQueueService, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(urlRepository.findById(LINK_ID)).thenReturn(Optional.of(link));
        lenient().when(urlRepository.findLongUrlById(LINK_ID)).thenReturn(Optional.of(DESTINATION));
        lenient().when(linkCheckService.check(anyString())).thenReturn(LinkVerdict.clean());
        lenient().when(safeBrowsingService.checkUrlsForThreats(anyList())).thenReturn(List.of());
    }

    @Test
    void followsAtMostFiveHopsWithinFiveSecondsAndRecordsWhereTheChainEnds() {
        chain(DESTINATION, TRACKER, "https://shop.example/offer");

        final var result = redirectChainService.check(LINK_ID);

        assertThat(result).isEqualTo(Result.CHECKED);
        verify(redirectFollower).followRedirects(DESTINATION, 5, Duration.ofSeconds(5));
        verify(urlRepository).recordRedirectChain(LINK_ID, DESTINATION, "https://shop.example/offer", CHECKED_AT);
        verify(urlRepository, never()).save(any(Url.class));
        verifyNoInteractions(moderationService, reviewQueueService);
    }

    @Test
    void aHopOnABlockListBlocksTheLinkNamingTheListAndTheHost() {
        chain(DESTINATION, TRACKER, PHISHING);
        when(linkCheckService.check(PHISHING)).thenReturn(blocked(BlockReason.BLOCKLIST, "URLhaus"));

        redirectChainService.check(LINK_ID);

        verify(moderationService).blockLink(LINK_ID, BlockReason.BLOCKLIST, "URLhaus via redirect to njuskaio.cam", "system", "redirect-check");
        verifyNoInteractions(reviewQueueService);
        verify(urlRepository).recordRedirectChain(LINK_ID, DESTINATION, PHISHING, CHECKED_AT);
    }

    @Test
    void aFlaggedHopQueuesTheLinkWithTheHostAndEachReason() {
        chain(DESTINATION, PHISHING);
        when(linkCheckService.check(PHISHING)).thenReturn(new LinkVerdict(Outcome.FLAG, List.of("on list HaGeZi badware hosters"), null, null));

        redirectChainService.check(LINK_ID);

        verify(reviewQueueService).flag(link, List.of("redirects to njuskaio.cam: on list HaGeZi badware hosters"));
        verifyNoInteractions(moderationService);
    }

    @Test
    void scamSignalsOnAHopQueueTheLinkInsteadOfBlockingIt() {
        chain(DESTINATION, PHISHING);
        when(linkCheckService.check(PHISHING)).thenReturn(new LinkVerdict(Outcome.BLOCK, YOUNG_LOOKALIKE, BlockReason.SCAM_SIGNALS,
                "domain registered in the last 30 days, imitates njuskalo"));

        redirectChainService.check(LINK_ID);

        verify(reviewQueueService).flag(link, List.of(
                "redirects to njuskaio.cam: domain registered in the last 30 days",
                "redirects to njuskaio.cam: imitates njuskalo"));
        verifyNoInteractions(moderationService);
    }

    @Test
    void safeBrowsingHitsOnHopsBlockTheLink() {
        chain(DESTINATION, TRACKER, PHISHING);
        when(safeBrowsingService.checkUrlsForThreats(List.of(TRACKER, PHISHING))).thenReturn(List.of(PHISHING, PHISHING));

        redirectChainService.check(LINK_ID);

        verify(moderationService).blockLink(LINK_ID, BlockReason.SAFE_BROWSING, PHISHING + " via redirect to njuskaio.cam", "system",
                "redirect-check");
        verifyNoInteractions(reviewQueueService);
        verify(urlRepository).recordRedirectChain(LINK_ID, DESTINATION, PHISHING, CHECKED_AT);
    }

    @Test
    void theWorstHopWins() {
        chain(DESTINATION, TRACKER, PHISHING);
        when(linkCheckService.check(TRACKER)).thenReturn(new LinkVerdict(Outcome.FLAG, List.of("link shortener"), null, null));
        when(linkCheckService.check(PHISHING)).thenReturn(blocked(BlockReason.DOMAIN_RULE, "njuskaio.cam"));

        redirectChainService.check(LINK_ID);

        verify(moderationService).blockLink(LINK_ID, BlockReason.DOMAIN_RULE, "njuskaio.cam via redirect to njuskaio.cam", "system",
                "redirect-check");
        verifyNoInteractions(reviewQueueService);
    }

    @Test
    void flagsFromSeveralHopsGoToOneReview() {
        chain(DESTINATION, TRACKER, PHISHING);
        when(linkCheckService.check(TRACKER)).thenReturn(new LinkVerdict(Outcome.FLAG, List.of("link shortener"), null, null));
        when(linkCheckService.check(PHISHING)).thenReturn(new LinkVerdict(Outcome.FLAG, List.of("imitates njuskalo"), null, null));

        redirectChainService.check(LINK_ID);

        verify(reviewQueueService).flag(link, List.of("redirects to track.example: link shortener", "redirects to njuskaio.cam: imitates njuskalo"));
    }

    @Test
    void onlyTheHopsAfterTheDestinationAreJudged() {
        chain(DESTINATION, TRACKER, DESTINATION, TRACKER);

        redirectChainService.check(LINK_ID);

        verify(safeBrowsingService).checkUrlsForThreats(List.of(TRACKER));
        verify(linkCheckService).check(TRACKER);
        verify(linkCheckService, never()).check(DESTINATION);
    }

    @Test
    void aDestinationThatDoesNotRedirectIsItsOwnFinalUrl() {
        chain(DESTINATION);

        redirectChainService.check(LINK_ID);

        verify(urlRepository).recordRedirectChain(LINK_ID, DESTINATION, DESTINATION, CHECKED_AT);
        verifyNoInteractions(linkCheckService, safeBrowsingService, moderationService, reviewQueueService);
    }

    @Test
    void aLinkAllowedByADomainRuleStillHasItsHopsChecked() {
        chain(DESTINATION, PHISHING);
        lenient().when(linkCheckService.isAllowListed(DESTINATION)).thenReturn(true);
        lenient().when(linkCheckService.check(DESTINATION)).thenReturn(
                new LinkVerdict(Outcome.ALLOWED, List.of("allowed domain innocent.example"), null, null));
        when(linkCheckService.check(PHISHING)).thenReturn(blocked(BlockReason.BLOCKLIST, "Phishing.Database"));

        redirectChainService.check(LINK_ID);

        verify(moderationService).blockLink(LINK_ID, BlockReason.BLOCKLIST, "Phishing.Database via redirect to njuskaio.cam", "system",
                "redirect-check");
    }

    @ParameterizedTest
    @ValueSource(strings = {"connection failed", "timed out", "not a public address"})
    void aFetchErrorOrARefusedDestinationRecordsOnlyTheCheckTime(final String reason) {
        when(redirectFollower.followRedirects(anyString(), eq(5), any(Duration.class))).thenThrow(new UrlValidationException(reason));

        final var result = redirectChainService.check(LINK_ID);

        assertThat(result).isEqualTo(Result.UNREACHABLE);
        verify(urlRepository).markRedirectChainChecked(LINK_ID, DESTINATION, CHECKED_AT);
        verify(urlRepository, never()).recordRedirectChain(anyLong(), anyString(), anyString(), any(LocalDateTime.class));
        verifyNoInteractions(linkCheckService, safeBrowsingService, moderationService, reviewQueueService);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void whenSafeBrowsingIsDownTheHopsAreStillCheckedButTheChainStaysDueForTheNextScan(final CapturedOutput output) {
        chain(DESTINATION, PHISHING);
        when(safeBrowsingService.checkUrlsForThreats(List.of(PHISHING)))
                .thenThrow(new UrlValidationException("URL safety check is temporarily unavailable. Please try again later."));
        when(linkCheckService.check(PHISHING)).thenReturn(blocked(BlockReason.BLOCKLIST, "URLhaus"));

        final var result = redirectChainService.check(LINK_ID);

        assertThat(result).isEqualTo(Result.SAFE_BROWSING_UNAVAILABLE);
        assertThat(output).contains("Safe Browsing failed").doesNotContain("\tat cc.hrva");
        verify(moderationService).blockLink(LINK_ID, BlockReason.BLOCKLIST, "URLhaus via redirect to njuskaio.cam", "system", "redirect-check");
        verify(urlRepository, never()).recordRedirectChain(anyLong(), anyString(), anyString(), any(LocalDateTime.class));
        verify(urlRepository, never()).markRedirectChainChecked(anyLong(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void aLongBlockDetailIsCutToTheColumnButKeepsTheHost() {
        final var longThreat = "https://njuskaio.cam/" + "a".repeat(300);
        chain(DESTINATION, longThreat);
        when(safeBrowsingService.checkUrlsForThreats(List.of(longThreat))).thenReturn(List.of(longThreat));
        final var detail = ArgumentCaptor.forClass(String.class);

        redirectChainService.check(LINK_ID);

        verify(moderationService).blockLink(eq(LINK_ID), eq(BlockReason.SAFE_BROWSING), detail.capture(), eq("system"), eq("redirect-check"));
        assertThat(detail.getValue()).hasSize(255).startsWith("https://njuskaio.cam/aaa").endsWith(" via redirect to njuskaio.cam");
    }

    @Test
    void aVeryLongFinalUrlIsCutToTheColumn() {
        final var landing = "https://shop.example/" + "b".repeat(3000);
        chain(DESTINATION, landing);

        redirectChainService.check(LINK_ID);

        verify(urlRepository).recordRedirectChain(LINK_ID, DESTINATION, landing.substring(0, 2048), CHECKED_AT);
    }

    @Test
    void aLinkThatIsNoLongerActiveIsNotFollowed() {
        link.setActive(false);

        final var result = redirectChainService.check(LINK_ID);

        assertThat(result).isEqualTo(Result.SKIPPED);
        verifyNoInteractions(redirectFollower, linkCheckService, safeBrowsingService, moderationService, reviewQueueService);
        verify(urlRepository, never()).recordRedirectChain(anyLong(), anyString(), anyString(), any(LocalDateTime.class));
        verify(urlRepository, never()).markRedirectChainChecked(anyLong(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void checkLaterRunsTheSameCheckInTheBackground() throws NoSuchMethodException {
        chain(DESTINATION, TRACKER);

        redirectChainService.checkLater(LINK_ID);

        verify(urlRepository).recordRedirectChain(LINK_ID, DESTINATION, TRACKER, CHECKED_AT);
        assertThat(DefaultRedirectChainService.class.getMethod("checkLater", long.class).getAnnotation(Async.class).value())
                .isEqualTo("redirectCheckExecutor");
    }

    @Test
    void aLinkRepointedWhileItsChainWasFollowedIsNotBlockedForTheOldChain() {
        chain(DESTINATION, PHISHING);
        when(linkCheckService.check(PHISHING)).thenReturn(blocked(BlockReason.BLOCKLIST, "URLhaus"));
        when(urlRepository.findLongUrlById(LINK_ID)).thenReturn(Optional.of("https://new.example/"));

        final var result = redirectChainService.check(LINK_ID);

        assertThat(result).isEqualTo(Result.SKIPPED);
        verifyNoInteractions(moderationService, reviewQueueService);
        verify(urlRepository, never()).recordRedirectChain(anyLong(), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void aLinkRepointedWhileItsChainWasFollowedIsNotQueuedForTheOldChain() {
        chain(DESTINATION, PHISHING);
        when(linkCheckService.check(PHISHING)).thenReturn(new LinkVerdict(Outcome.FLAG, List.of("imitates njuskalo"), null, null));
        when(urlRepository.findLongUrlById(LINK_ID)).thenReturn(Optional.of("https://new.example/"));

        final var result = redirectChainService.check(LINK_ID);

        assertThat(result).isEqualTo(Result.SKIPPED);
        verifyNoInteractions(moderationService, reviewQueueService);
    }

    @Test
    void aLinkDeletedWhileItsChainWasFollowedIsLeftAlone() {
        chain(DESTINATION, PHISHING);
        when(linkCheckService.check(PHISHING)).thenReturn(blocked(BlockReason.BLOCKLIST, "URLhaus"));
        when(urlRepository.findLongUrlById(LINK_ID)).thenReturn(Optional.empty());

        assertThat(redirectChainService.check(LINK_ID)).isEqualTo(Result.SKIPPED);
        verifyNoInteractions(moderationService, reviewQueueService);
    }

    @Test
    void aCleanChainNeedsNoSecondLookOfTheLink() {
        chain(DESTINATION, TRACKER);

        redirectChainService.check(LINK_ID);

        verify(urlRepository, never()).findLongUrlById(anyLong());
    }

    @Test
    void checksRunOutsideAnyTransactionSoEachBlockAndFlagCommitsOnItsOwn() {
        final var transactional = DefaultRedirectChainService.class.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    private void chain(final String... urls) {
        when(redirectFollower.followRedirects(DESTINATION, 5, Duration.ofSeconds(5))).thenReturn(List.of(urls));
    }

    private static LinkVerdict blocked(final BlockReason reason, final String detail) {
        return new LinkVerdict(Outcome.BLOCK, List.of("on list " + detail), reason, detail);
    }

}
