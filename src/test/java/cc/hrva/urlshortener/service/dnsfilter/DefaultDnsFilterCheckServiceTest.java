package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Async;

import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterProvider.CLEANBROWSING;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterProvider.CLOUDFLARE;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterProvider.CONTROL_D;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterProvider.QUAD9;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterStatus.BLOCKED;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterStatus.NOT_BLOCKED;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterStatus.NO_ANSWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultDnsFilterCheckServiceTest {

    private static final long LINK_ID = 7L;
    private static final String HOST = "poshmark.id653655.shop";
    private static final String DESTINATION = "https://" + HOST + "/1";

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private DnsFilterService dnsFilterService;

    @Mock
    private SettingsService settingsService;

    @Mock
    private LinkCheckService linkCheckService;

    @Mock
    private ModerationService moderationService;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private OwnHostsCheck ownHostsCheck;

    private DnsFilterCheckService service;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T12:00:00Z"));

    private final Url link = Url.builder().id(LINK_ID).shortUrl("abc").longUrl(DESTINATION).active(true).build();

    @BeforeEach
    void setUp() {
        service = new DefaultDnsFilterCheckService(urlRepository, dnsFilterService, settingsService, linkCheckService, moderationService, reviewQueueService, ownHostsCheck, clock);
        lenient().when(urlRepository.findById(LINK_ID)).thenReturn(Optional.of(link));
        lenient().when(settingsService.isOn(SettingKey.DNS_FILTER_CHECK)).thenReturn(true);
        lenient().when(settingsService.number(SettingKey.DNS_FILTERS_TO_BLOCK)).thenReturn(2);
    }

    @Test
    void twoFiltersBlockTheLinkAndEveryOtherLinkToTheHost() {
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, BLOCKED), answer(CLOUDFLARE, BLOCKED), answer(CLEANBROWSING, NOT_BLOCKED)));
        when(urlRepository.findActiveIdsPointingAt(HOST)).thenReturn(List.of(LINK_ID, 8L, 9L));

        final var result = service.check(LINK_ID);

        assertThat(result).isEqualTo(DnsFilterCheckResult.BLOCKED);
        verify(moderationService).blockLink(LINK_ID, BlockReason.DNS_FILTERS, "Quad9, Cloudflare", "system", "dns-filter-check");
        verify(moderationService).blockLink(8L, BlockReason.DNS_FILTERS, "Quad9, Cloudflare", "system", "dns-filter-check");
        verify(moderationService).blockLink(9L, BlockReason.DNS_FILTERS, "Quad9, Cloudflare", "system", "dns-filter-check");
        verifyNoInteractions(reviewQueueService);
    }

    @Test
    void theThresholdSettingIsRespected() {
        when(settingsService.number(SettingKey.DNS_FILTERS_TO_BLOCK)).thenReturn(3);
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, BLOCKED), answer(CLOUDFLARE, BLOCKED), answer(CONTROL_D, NOT_BLOCKED)));

        final var result = service.check(LINK_ID);

        assertThat(result).isEqualTo(DnsFilterCheckResult.FLAGGED);
        verify(reviewQueueService).flag(link, List.of("blocked by DNS filter Quad9", "blocked by DNS filter Cloudflare"));
        verifyNoInteractions(moderationService);
    }

    @Test
    void oneFilterFlagsWhateverTheDomainAge() {
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, BLOCKED), answer(CLOUDFLARE, NOT_BLOCKED)));

        final var result = service.check(LINK_ID);

        assertThat(result).isEqualTo(DnsFilterCheckResult.FLAGGED);
        verify(reviewQueueService).flag(link, List.of("blocked by DNS filter Quad9"));
        verifyNoInteractions(moderationService);
    }

    @Test
    void theFinalHostOfARedirectIsCheckedToo() {
        link.setFinalUrl("https://evil.example/landing");
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, NOT_BLOCKED)));
        when(dnsFilterService.lookup("evil.example")).thenReturn(report("evil.example", answer(QUAD9, BLOCKED), answer(CLOUDFLARE, NOT_BLOCKED)));

        final var result = service.check(LINK_ID);

        assertThat(result).isEqualTo(DnsFilterCheckResult.FLAGGED);
        verify(reviewQueueService).flag(link, List.of("redirects to evil.example: blocked by DNS filter Quad9"));
    }

    @Test
    void twoFiltersBlockingTheFinalHostBlockTheLink() {
        link.setFinalUrl("https://evil.example/landing");
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, NOT_BLOCKED)));
        when(dnsFilterService.lookup("evil.example")).thenReturn(report("evil.example", answer(QUAD9, BLOCKED), answer(CONTROL_D, BLOCKED)));
        when(urlRepository.findActiveIdsPointingAt("evil.example")).thenReturn(List.of());

        final var result = service.check(LINK_ID);

        assertThat(result).isEqualTo(DnsFilterCheckResult.BLOCKED);
        verify(moderationService).blockLink(LINK_ID, BlockReason.DNS_FILTERS, "Quad9, Control D", "system", "dns-filter-check");
    }

    @Test
    void aFinalHostEqualToTheDestinationIsLookedUpOnce() {
        link.setFinalUrl("https://" + HOST.toUpperCase() + "/other");
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, NOT_BLOCKED)));

        assertThat(service.check(LINK_ID)).isEqualTo(DnsFilterCheckResult.CLEAN);

        verify(dnsFilterService).lookup(HOST);
        verifyNoMoreInteractions(dnsFilterService);
    }

    @Test
    void anAllowRuleOnTheFinalHostSkipsThatHost() {
        link.setFinalUrl("https://allowed.example/landing");
        lenient().when(linkCheckService.isAllowListed(DESTINATION)).thenReturn(false);
        when(linkCheckService.isAllowListed("https://allowed.example/landing")).thenReturn(true);
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, NOT_BLOCKED)));

        final var result = service.check(LINK_ID);

        assertThat(result).isEqualTo(DnsFilterCheckResult.CLEAN);
        verify(dnsFilterService, never()).lookup("allowed.example");
        verifyNoInteractions(reviewQueueService, moderationService);
    }

    @Test
    void noBlockWithAnUnansweredFilterIsRetry() {
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, NOT_BLOCKED), answer(CLOUDFLARE, NO_ANSWER)));

        final var result = service.check(LINK_ID);

        assertThat(result).isEqualTo(DnsFilterCheckResult.RETRY);
        verifyNoInteractions(reviewQueueService, moderationService);
    }

    @Test
    void aHostThatDoesNotExistIsClean() {
        when(dnsFilterService.lookup(HOST)).thenReturn(new DnsFilterReport(HOST, false, List.of(answer(QUAD9, NO_ANSWER))));

        final var result = service.check(LINK_ID);

        assertThat(result).isEqualTo(DnsFilterCheckResult.CLEAN);
        verifyNoInteractions(reviewQueueService, moderationService);
    }

    @Test
    void anAllowRuleSkipsTheCheck() {
        when(linkCheckService.isAllowListed(DESTINATION)).thenReturn(true);

        assertThat(service.check(LINK_ID)).isEqualTo(DnsFilterCheckResult.SKIPPED);

        verifyNoInteractions(dnsFilterService, reviewQueueService, moderationService);
    }

    @Test
    void anIpDestinationAnInactiveLinkOrTheCheckTurnedOffAreSkipped() {
        link.setLongUrl("http://203.0.113.9/login");
        assertThat(service.check(LINK_ID)).isEqualTo(DnsFilterCheckResult.SKIPPED);

        link.setLongUrl(DESTINATION);
        link.setActive(false);
        assertThat(service.check(LINK_ID)).isEqualTo(DnsFilterCheckResult.SKIPPED);

        link.setActive(true);
        when(settingsService.isOn(SettingKey.DNS_FILTER_CHECK)).thenReturn(false);
        assertThat(service.check(LINK_ID)).isEqualTo(DnsFilterCheckResult.SKIPPED);

        verifyNoInteractions(dnsFilterService, reviewQueueService, moderationService);
    }

    @Test
    void aMissingLinkIsSkipped() {
        when(urlRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.check(99L)).isEqualTo(DnsFilterCheckResult.SKIPPED);

        verifyNoInteractions(dnsFilterService);
    }

    @Test
    void blockingFiltersNamesTheFiltersThatStillBlockATurnedOffLink() {
        link.setActive(false);
        link.setBlockReason(BlockReason.DNS_FILTERS);
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, BLOCKED), answer(CLOUDFLARE, BLOCKED), answer(CONTROL_D, NOT_BLOCKED)));

        assertThat(service.blockingFilters(link)).containsExactly("Quad9", "Cloudflare");
        verifyNoInteractions(moderationService, reviewQueueService);
    }

    @Test
    void blockingFiltersNamesTheFiltersThatStillBlockARedirectsFinalHost() {
        link.setActive(false);
        link.setFinalUrl("https://evil.example/landing");
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, NOT_BLOCKED)));
        when(dnsFilterService.lookup("evil.example")).thenReturn(report("evil.example", answer(QUAD9, BLOCKED), answer(CONTROL_D, BLOCKED)));

        assertThat(service.blockingFilters(link)).containsExactly("Quad9", "Control D");
    }

    @Test
    void blockingFiltersIsEmptyUnderAnAllowRule() {
        link.setActive(false);
        when(linkCheckService.isAllowListed(DESTINATION)).thenReturn(true);

        assertThat(service.blockingFilters(link)).isEmpty();
        verifyNoInteractions(dnsFilterService);
    }

    @Test
    void blockingFiltersIsEmptyWhenFewerFiltersThanTheThresholdStillBlock() {
        link.setActive(false);
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, BLOCKED), answer(CLOUDFLARE, NOT_BLOCKED)));

        assertThat(service.blockingFilters(link)).isEmpty();
    }

    @Test
    void checkLaterRunsOnTheDnsFilterExecutor() throws NoSuchMethodException {
        final var async = DefaultDnsFilterCheckService.class.getMethod("checkLater", long.class).getAnnotation(Async.class);

        assertThat(async.value()).isEqualTo("dnsFilterExecutor");
    }

    @Test
    void checkLaterSwallowsAFailureWithAWarnLine(final CapturedOutput output) {
        when(dnsFilterService.lookup(HOST)).thenThrow(new IllegalStateException("resolver down"));

        service.checkLater(LINK_ID);

        assertThat(output.getOut()).contains("Could not check a link against the DNS filters urlId=7");
        verify(moderationService, never()).blockLink(anyLong(), any(), any(), any(), any());
        verify(reviewQueueService, never()).flag(any(), anyList());
    }

    @Test
    void recheckFreshPicksLinksOneSixAndTwentyFourHoursOld() {
        when(urlRepository.findActiveIdsCreatedBetween(LocalDateTime.of(2026, 11, 19, 10, 45), LocalDateTime.of(2026, 11, 19, 11, 0))).thenReturn(List.of(LINK_ID, 8L));
        when(urlRepository.findActiveIdsCreatedBetween(LocalDateTime.of(2026, 11, 19, 5, 45), LocalDateTime.of(2026, 11, 19, 6, 0))).thenReturn(List.of(LINK_ID));
        when(urlRepository.findActiveIdsCreatedBetween(LocalDateTime.of(2026, 11, 18, 11, 45), LocalDateTime.of(2026, 11, 18, 12, 0))).thenReturn(List.of(9L));
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, NOT_BLOCKED)));

        service.recheckFresh();

        verify(urlRepository, times(1)).findById(LINK_ID);
        verify(urlRepository).findById(8L);
        verify(urlRepository).findById(9L);
        verify(dnsFilterService, times(1)).lookup(HOST);
    }

    @Test
    void recheckFreshChecksNothingWhenNoLinkIsThatOld() {
        service.recheckFresh();

        verify(urlRepository, never()).findById(anyLong());
        verifyNoInteractions(dnsFilterService);
    }

    @Test
    void sweepChecksEveryActiveLinkAndLogsTheCountsPerResult(final CapturedOutput output) {
        when(urlRepository.findActiveIds()).thenReturn(List.of(LINK_ID, 8L, 99L));
        when(urlRepository.findById(8L)).thenReturn(Optional.of(Url.builder().id(8L).shortUrl("def").longUrl("https://evil.example/").active(true).build()));
        when(urlRepository.findById(99L)).thenReturn(Optional.empty());
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, NOT_BLOCKED)));
        when(dnsFilterService.lookup("evil.example")).thenReturn(report("evil.example", answer(QUAD9, BLOCKED)));

        service.sweep();

        verify(dnsFilterService).lookup(HOST);
        verify(dnsFilterService).lookup("evil.example");
        verify(ownHostsCheck).run();
        assertThat(output.getOut()).contains("DNS filter sweep finished links=3 results={BLOCKED=0, FLAGGED=1, CLEAN=1, RETRY=0, SKIPPED=1}");
    }

    @Test
    void twoLinksToOneHostAreLookedUpOncePerSweepEvenWithoutTheCache() {
        when(urlRepository.findActiveIds()).thenReturn(List.of(LINK_ID, 8L));
        when(urlRepository.findById(8L)).thenReturn(Optional.of(Url.builder().id(8L).shortUrl("def").longUrl("https://" + HOST + "/2").active(true).build()));
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, BLOCKED), answer(CLOUDFLARE, NOT_BLOCKED)));

        service.sweep();

        verify(dnsFilterService, times(1)).lookup(HOST);
        verify(reviewQueueService, times(2)).flag(any(), eq(List.of("blocked by DNS filter Quad9")));
    }

    @Test
    void twoLinksToOneHostAreLookedUpOncePerRecheck() {
        when(urlRepository.findActiveIdsCreatedBetween(LocalDateTime.of(2026, 11, 19, 10, 45), LocalDateTime.of(2026, 11, 19, 11, 0))).thenReturn(List.of(LINK_ID, 8L));
        when(urlRepository.findById(8L)).thenReturn(Optional.of(Url.builder().id(8L).shortUrl("def").longUrl("https://" + HOST + "/2").active(true).build()));
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, NOT_BLOCKED), answer(CLOUDFLARE, NO_ANSWER)));

        service.recheckFresh();

        verify(dnsFilterService, times(1)).lookup(HOST);
    }

    @Test
    void separateChecksOfOneHostEachAskTheLookup() {
        when(dnsFilterService.lookup(HOST)).thenReturn(report(HOST, answer(QUAD9, NOT_BLOCKED)));

        service.check(LINK_ID);
        service.check(LINK_ID);

        verify(dnsFilterService, times(2)).lookup(HOST);
    }

    @Test
    void sweepKeepsGoingWhenOneLinkFails(final CapturedOutput output) {
        when(urlRepository.findActiveIds()).thenReturn(List.of(LINK_ID, 8L));
        when(urlRepository.findById(8L)).thenReturn(Optional.of(Url.builder().id(8L).shortUrl("def").longUrl("https://fine.example/").active(true).build()));
        when(dnsFilterService.lookup(HOST)).thenThrow(new IllegalStateException("resolver down"));
        when(dnsFilterService.lookup("fine.example")).thenReturn(report("fine.example", answer(QUAD9, NOT_BLOCKED)));

        service.sweep();

        assertThat(output.getOut()).contains("Could not check a link against the DNS filters urlId=7").contains("failed=1").contains("CLEAN=1");
    }

    @Test
    void aSweepThatIsStillRunningSkipsTheNextOne(final CapturedOutput output) throws Exception {
        when(urlRepository.findActiveIds()).thenReturn(List.of(LINK_ID));
        final var inside = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        when(dnsFilterService.lookup(HOST)).thenAnswer(invocation -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return report(HOST, answer(QUAD9, NOT_BLOCKED));
        });
        final var first = CompletableFuture.runAsync(service::sweep);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        service.sweep();
        release.countDown();
        first.get(5, TimeUnit.SECONDS);

        assertThat(output.getOut()).contains("DNS filter sweep still running, this run skipped");
        verify(urlRepository, times(1)).findActiveIds();
        verify(dnsFilterService, times(1)).lookup(HOST);
        service.sweep();
        verify(urlRepository, times(2)).findActiveIds();
    }

    @Test
    void aRecheckThatIsStillRunningSkipsTheNextOne(final CapturedOutput output) throws Exception {
        when(urlRepository.findActiveIdsCreatedBetween(LocalDateTime.of(2026, 11, 19, 10, 45), LocalDateTime.of(2026, 11, 19, 11, 0))).thenReturn(List.of(LINK_ID));
        final var inside = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        when(dnsFilterService.lookup(HOST)).thenAnswer(invocation -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return report(HOST, answer(QUAD9, NOT_BLOCKED));
        });
        final var first = CompletableFuture.runAsync(service::recheckFresh);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        service.recheckFresh();
        release.countDown();
        first.get(5, TimeUnit.SECONDS);

        assertThat(output.getOut()).contains("DNS filter recheck still running, this run skipped");
        verify(dnsFilterService, times(1)).lookup(HOST);
    }

    @Test
    void theRechecksAndTheSweepRunInTheBackground() throws NoSuchMethodException {
        assertThat(DefaultDnsFilterCheckService.class.getMethod("recheckFresh").getAnnotation(Async.class).value()).isEqualTo("dnsFilterSweepExecutor");
        assertThat(DefaultDnsFilterCheckService.class.getMethod("sweep").getAnnotation(Async.class).value()).isEqualTo("dnsFilterSweepExecutor");
    }

    private static DnsFilterReport report(final String host, final DnsFilterAnswer... answers) {
        return new DnsFilterReport(host, true, List.of(answers));
    }

    private static DnsFilterAnswer answer(final DnsFilterProvider provider, final DnsFilterStatus status) {
        return new DnsFilterAnswer(provider, status, null);
    }

}
