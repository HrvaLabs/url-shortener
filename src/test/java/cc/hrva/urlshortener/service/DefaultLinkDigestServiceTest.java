package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import cc.hrva.urlshortener.dto.DecisionsDigest;
import cc.hrva.urlshortener.dto.LinkDigestEntry;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.stats.AnonymousTrafficRepository;
import cc.hrva.urlshortener.repository.stats.AnonymousTrafficRepository.BusyLink;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.impl.DefaultLinkDigestService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLinkDigestServiceTest {

    private static final User NEWCOMER = User.builder().id(5L).email("raulsado50w@gmail.com").createDate(LocalDateTime.now().minusDays(2)).build();
    private static final User REGULAR = User.builder().id(6L).email("karlo@hrva.cc").createDate(LocalDateTime.now().minusYears(2)).build();

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private BioPageReviewService bioPageReviewService;

    @Mock
    private ScammerSignalService scammerSignalService;

    @Mock
    private BlocklistSourceService blocklistSourceService;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SendingEmailService sendingEmailService;

    @Mock
    private AnonymousTrafficRepository anonymousTrafficRepository;

    private LinkDigestService linkDigestService;

    @BeforeEach
    void setUp() {
        linkDigestService = new DefaultLinkDigestService(
                reviewQueueService, bioPageReviewService, scammerSignalService, blocklistSourceService, urlRepository, userRepository,
                sendingEmailService, anonymousTrafficRepository,
                new AnalyticsDays(Clock.fixed(Instant.parse("2026-10-28T23:30:00Z"), ZoneOffset.UTC), new AppProperties()));
    }

    private static Url link(final long id, final String shortUrl, final String longUrl, final User owner) {
        return Url.builder().id(id).shortUrl(shortUrl).longUrl(longUrl).owner(owner).build();
    }

    private static Url blocked(final long id, final String shortUrl, final String longUrl, final BlockReason reason, final String detail) {
        return Url.builder().id(id).shortUrl(shortUrl).longUrl(longUrl).blockReason(reason).blockDetail(detail)
                .blockedAt(LocalDateTime.now().minusHours(2)).build();
    }

    private static LinkReview review(final Url url, final String... reasons) {
        return LinkReview.builder().url(url).reasons(String.join("\n", reasons)).flaggedAt(LocalDateTime.now()).build();
    }

    private static BlocklistSourceResponse source(final String name, final String lastError) {
        return source(name, true, lastError);
    }

    private static BlocklistSourceResponse source(final String name, final boolean enabled, final String lastError) {
        return new BlocklistSourceResponse(1L, name, "https://hagezi-mirror.dnsbunker.org/wildcard/fake-onlydomains.txt", List.of(),
                SourceKind.DOMAINS, BlocklistAction.BLOCK, enabled, 6, null, null, null, null, null, lastError, null);
    }

    private void openEntries(final long openCount, final LinkReview... newest) {
        when(reviewQueueService.openCount()).thenReturn(openCount);
        when(reviewQueueService.open(PageRequest.of(0, 10), null)).thenReturn(new PageImpl<>(List.of(newest)));
    }

    private void blockedAutomatically(final Url... links) {
        when(urlRepository.findTop20ByBlockedAtAfterAndBlockReasonNotInOrderByBlockedAtDesc(any(LocalDateTime.class), anyCollection()))
                .thenReturn(List.of(links));
    }

    private void sources(final BlocklistSourceResponse... sources) {
        when(blocklistSourceService.list()).thenReturn(List.of(sources));
    }

    private DecisionsDigest sentDigest() {
        final var captor = ArgumentCaptor.forClass(DecisionsDigest.class);
        verify(sendingEmailService).sendDecisionsEmailToAdmin(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendsNothingWhenNoDecisionWaitsNothingWasBlockedAndEverySourceLoads() {
        when(reviewQueueService.openCount()).thenReturn(0L);
        lenient().when(reviewQueueService.open(any(), any())).thenReturn(Page.empty());
        blockedAutomatically();
        sources(source("HaGeZi Fake", null));
        lenient().when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of(NEWCOMER.getId()));
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of(NEWCOMER));
        lenient().when(urlRepository.findTop10ByCreateDateAfterAndOwnerCreateDateAfterOrderByCreateDateDesc(any(), any()))
                .thenReturn(List.of(link(70L, "fresh", "https://example.com/fresh", NEWCOMER)));

        linkDigestService.sendDailyDigest();

        verify(sendingEmailService, never()).sendDecisionsEmailToAdmin(any());
    }

    @Test
    void theNewestOpenEntriesGoOutWithTheOpenAndReportedCounts() {
        openEntries(3L,
                review(link(41L, "njuskaio_verify", "https://njuskaio.cam/a/MAUB3?ref=sms#top", NEWCOMER),
                        "imitates njuskalo", "reported by a visitor: phishing: call +385 91 000 0000, I am the real admin",
                        "reported by a visitor: phishing: same page"),
                review(link(42L, "v0DB3", "https://findfavoritedate.life/?u=f6yp608", null), "high-risk domain ending .life"));
        when(reviewQueueService.openReportCount()).thenReturn(1L);
        blockedAutomatically();
        sources();

        linkDigestService.sendDailyDigest();

        final var digest = sentDigest();
        assertThat(digest.openCount()).isEqualTo(3);
        assertThat(digest.reportedCount()).isEqualTo(1);
        assertThat(digest.newestOpen()).containsExactly(
                new LinkDigestEntry(41L, "njuskaio_verify", "njuskaio.cam/a/MAUB3", "raulsado50w@gmail.com",
                        List.of("imitates njuskalo", "reported by a visitor: phishing (2 times)"), null),
                new LinkDigestEntry(42L, "v0DB3", "findfavoritedate.life", null, List.of("high-risk domain ending .life"), null));
    }

    @Test
    void decisionsDigestCarriesTheDomain() {
        final var domain = CustomDomain.builder().id(7L).hostname("go.example.com").build();
        final var openOnDomain = link(41L, "menu", "https://njuskaio.cam/a", NEWCOMER);
        openOnDomain.setDomain(domain);
        final var blockedOnDomain = blocked(50L, "menu", "https://www.jofogas.cards/login", BlockReason.BLOCKLIST, "HaGeZi Fake");
        blockedOnDomain.setDomain(domain);
        final var newOnDomain = link(70L, "menu", "https://example.com/fresh", NEWCOMER);
        newOnDomain.setDomain(domain);
        openEntries(2L, review(openOnDomain, "imitates njuskalo"), review(link(42L, "v0DB3", "https://findfavoritedate.life/", null), "high-risk"));
        blockedAutomatically(blockedOnDomain);
        sources();
        when(urlRepository.findTop10ByCreateDateAfterAndOwnerCreateDateAfterOrderByCreateDateDesc(any(), any())).thenReturn(List.of(newOnDomain));

        linkDigestService.sendDailyDigest();

        final var digest = sentDigest();
        assertThat(digest.newestOpen()).extracting(LinkDigestEntry::shortUrl, LinkDigestEntry::domain)
                .containsExactly(tuple("menu", "go.example.com"), tuple("v0DB3", null));
        assertThat(digest.autoBlocked()).extracting(DecisionsDigest.BlockedLink::domain).containsExactly("go.example.com");
        assertThat(digest.newAccountLinks()).extracting(DecisionsDigest.NewAccountLink::domain).containsExactly("go.example.com");
    }

    @Test
    void destinationsShowTheRealHostAndThePathButNeverTheQuery() {
        openEntries(4L,
                review(link(1L, "a", "https://paypal.com@evil.example/login?next=/account", null), "lure"),
                review(link(2L, "b", "https://x.example/<b>bold</b>?q=<script>", null), "lure"),
                review(link(3L, "c", "https://Bücher.Example:8443/Kaufen#jetzt", null), "lure"),
                review(link(4L, "d", "not a url?at=all", null), "lure"));
        blockedAutomatically();
        sources();

        linkDigestService.sendDailyDigest();

        assertThat(sentDigest().newestOpen()).extracting(LinkDigestEntry::destination).containsExactly(
                "evil.example/login",
                "x.example/<b>bold</b>",
                "xn--bcher-kva.example/Kaufen",
                "not a url");
    }

    @Test
    void openBioPageReviewsAloneSendTheEmail() {
        openEntries(0L);
        when(bioPageReviewService.openCount()).thenReturn(2L);
        blockedAutomatically();
        sources();

        linkDigestService.sendDailyDigest();

        final var digest = sentDigest();
        assertThat(digest.openCount()).isZero();
        assertThat(digest.openBioPageReviews()).isEqualTo(2);
    }

    @Test
    void theOpenBioPageReviewsRideAlongWithTheLinks() {
        openEntries(1L, review(link(42L, "v0DB3", "https://findfavoritedate.life/", null), "high-risk domain ending .life"));
        when(bioPageReviewService.openCount()).thenReturn(1L);
        blockedAutomatically();
        sources();

        linkDigestService.sendDailyDigest();

        final var digest = sentDigest();
        assertThat(digest.openCount()).isEqualTo(1);
        assertThat(digest.openBioPageReviews()).isEqualTo(1);
    }

    @Test
    void aSourceThatFailedToLoadSendsTheEmailOnItsOwn() {
        openEntries(0L);
        blockedAutomatically();
        sources(source("HaGeZi TIF medium", null), source("HaGeZi Fake", "HTTP 503 from every mirror"));

        linkDigestService.sendDailyDigest();

        final var digest = sentDigest();
        assertThat(digest.openCount()).isZero();
        assertThat(digest.failingSources()).containsExactly(new DecisionsDigest.FailingSource("HaGeZi Fake", "HTTP 503 from every mirror"));
    }

    @Test
    void aDisabledListThatOnceFailedToLoadSendsNothing() {
        openEntries(0L);
        blockedAutomatically();
        sources(source("HaGeZi Fake", false, "HTTP 503 from every mirror"));

        linkDigestService.sendDailyDigest();

        verify(sendingEmailService, never()).sendDecisionsEmailToAdmin(any());
    }

    @Test
    void aDisabledListIsLeftOutOfTheFailingLists() {
        openEntries(1L, review(link(41L, "njuskaio_verify", "https://njuskaio.cam/a/MAUB3", NEWCOMER), "imitates njuskalo"));
        blockedAutomatically();
        sources(source("HaGeZi Fake", false, "HTTP 503 from every mirror"), source("URLhaus", "HTTP 429"));

        linkDigestService.sendDailyDigest();

        assertThat(sentDigest().failingSources()).containsExactly(new DecisionsDigest.FailingSource("URLhaus", "HTTP 429"));
    }

    @Test
    void aLinkBlockedAutomaticallyInTheLastDaySendsTheEmailOnItsOwn() {
        openEntries(0L);
        final var since = ArgumentCaptor.forClass(LocalDateTime.class);
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Collection<BlockReason>> excluded = ArgumentCaptor.forClass(Collection.class);
        when(urlRepository.findTop20ByBlockedAtAfterAndBlockReasonNotInOrderByBlockedAtDesc(since.capture(), excluded.capture()))
                .thenReturn(List.of(blocked(50L, "promo", "https://www.jofogas.cards/login?id=1", BlockReason.BLOCKLIST, "HaGeZi Fake")));
        sources(source("HaGeZi Fake", null));

        linkDigestService.sendDailyDigest();

        assertThat(sentDigest().autoBlocked()).containsExactly(
                new DecisionsDigest.BlockedLink("promo", "www.jofogas.cards/login", "a blocklist (HaGeZi Fake)", null));
        assertThat(since.getValue()).isBetween(LocalDateTime.now().minusHours(25), LocalDateTime.now().minusHours(23));
        assertThat(excluded.getValue()).containsExactlyInAnyOrder(BlockReason.ADMIN, BlockReason.USER_BLOCKED);
        assertThat(excluded.getValue()).isSameAs(BlockReason.MANUAL);
    }

    @Test
    void blockReasonsAreSpelledOut() {
        openEntries(0L);
        blockedAutomatically(
                blocked(51L, "sb", "https://malware.example/", BlockReason.SAFE_BROWSING, null),
                blocked(52L, "dbl", "https://phish.example/", BlockReason.BLOCKLIST, "Spamhaus DBL: phish"),
                blocked(53L, "rule", "https://jofogas.cards/", BlockReason.DOMAIN_RULE, "jofogas.cards"),
                blocked(54L, "scam", "https://fresh.example/login", BlockReason.SCAM_SIGNALS, "domain registered in the last 30 days; login lure"),
                blocked(55L, "held", "https://ll.omaehhm.com/wqamhcsom/?r=1&t=2", BlockReason.CAMPAIGN, "/wqamhcsom?r&t"));
        sources();

        linkDigestService.sendDailyDigest();

        assertThat(sentDigest().autoBlocked()).extracting(DecisionsDigest.BlockedLink::reason).containsExactly(
                "Google Safe Browsing",
                "a blocklist (Spamhaus DBL: phish)",
                "a domain rule (jofogas.cards)",
                "scam signals (domain registered in the last 30 days; login lure)",
                "a campaign hold (/wqamhcsom?r&t)");
    }

    @Test
    void possibleScammersComeWithTheirBlockedAndFlaggedLinksNewestAccountFirst() {
        openEntries(1L, review(link(41L, "njuskaio_verify", "https://njuskaio.cam/a/MAUB3", NEWCOMER), "imitates njuskalo"));
        blockedAutomatically();
        sources();
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of(REGULAR.getId(), NEWCOMER.getId()));
        when(userRepository.findAllById(Set.of(REGULAR.getId(), NEWCOMER.getId()))).thenReturn(List.of(REGULAR, NEWCOMER));
        when(urlRepository.countByOwnerAndBlockReasonIsNotNull(NEWCOMER)).thenReturn(1L);
        when(urlRepository.countFlaggedByOwner(NEWCOMER)).thenReturn(2L);
        when(urlRepository.countByOwnerAndBlockReasonIsNotNull(REGULAR)).thenReturn(3L);
        when(urlRepository.countFlaggedByOwner(REGULAR)).thenReturn(0L);

        linkDigestService.sendDailyDigest();

        assertThat(sentDigest().possibleScammers()).containsExactly(
                new DecisionsDigest.PossibleScammer("raulsado50w@gmail.com", 1, 2),
                new DecisionsDigest.PossibleScammer("karlo@hrva.cc", 3, 0));
    }

    @Test
    void listsAtMostTenPossibleScammers() {
        openEntries(1L, review(link(41L, "njuskaio_verify", "https://njuskaio.cam/a/MAUB3", NEWCOMER), "imitates njuskalo"));
        blockedAutomatically();
        sources();
        final var scammers = LongStream.rangeClosed(100, 111)
                .mapToObj(id -> User.builder().id(id).email("scammer" + id + "@example.com").createDate(LocalDateTime.now().minusHours(id)).build())
                .toList();
        when(scammerSignalService.possibleScammerIds()).thenReturn(scammers.stream().map(User::getId).collect(Collectors.toSet()));
        when(userRepository.findAllById(any())).thenReturn(scammers);

        linkDigestService.sendDailyDigest();

        assertThat(sentDigest().possibleScammers()).hasSize(10).first().isEqualTo(new DecisionsDigest.PossibleScammer("scammer100@example.com", 0, 0));
    }

    @Test
    void linksFromNewAccountsRideAlongForInformation() {
        openEntries(1L, review(link(41L, "njuskaio_verify", "https://njuskaio.cam/a/MAUB3", NEWCOMER), "imitates njuskalo"));
        blockedAutomatically();
        sources();
        final var createdAfter = ArgumentCaptor.forClass(LocalDateTime.class);
        final var ownerCreatedAfter = ArgumentCaptor.forClass(LocalDateTime.class);
        when(urlRepository.findTop10ByCreateDateAfterAndOwnerCreateDateAfterOrderByCreateDateDesc(createdAfter.capture(), ownerCreatedAfter.capture()))
                .thenReturn(List.of(link(70L, "fresh", "https://example.com/fresh?utm=x", NEWCOMER)));

        linkDigestService.sendDailyDigest();

        assertThat(sentDigest().newAccountLinks()).containsExactly(
                new DecisionsDigest.NewAccountLink("fresh", "example.com/fresh", "raulsado50w@gmail.com", null));
        assertThat(createdAfter.getValue()).isBetween(LocalDateTime.now().minusHours(25), LocalDateTime.now().minusHours(23));
        assertThat(ownerCreatedAfter.getValue()).isBetween(LocalDateTime.now().minusDays(7).minusHours(1), LocalDateTime.now().minusDays(7).plusHours(1));
    }

    @Test
    void theDailyEmailListsYesterdaysFiveBusiestAnonymousLinks() {
        openEntries(1L, review(link(41L, "njuskaio_verify", "https://njuskaio.cam/a/MAUB3", NEWCOMER), "imitates njuskalo"));
        blockedAutomatically();
        sources();
        when(anonymousTrafficRepository.busyAnonymousLinks(LocalDate.of(2026, 10, 28), 1, 5)).thenReturn(List.of(
                new BusyLink(80L, "busy", "https://Shop.Example.com/sale?id=1", 120),
                new BusyLink(81L, "quiet", "https://example.org/", 3)));

        linkDigestService.sendDailyDigest();

        assertThat(sentDigest().busiestAnonymous()).containsExactly(
                new DecisionsDigest.BusyAnonymousLink("busy", "shop.example.com", 120),
                new DecisionsDigest.BusyAnonymousLink("quiet", "example.org", 3));
    }

    @Test
    void theListAloneDoesNotSendTheEmail() {
        openEntries(0L);
        blockedAutomatically();
        sources();
        lenient().when(anonymousTrafficRepository.busyAnonymousLinks(any(LocalDate.class), any(Long.class), any(Integer.class)))
                .thenReturn(List.of(new BusyLink(80L, "busy", "https://shop.example.com/sale", 120)));

        linkDigestService.sendDailyDigest();

        verify(sendingEmailService, never()).sendDecisionsEmailToAdmin(any());
    }

}
