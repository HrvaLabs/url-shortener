package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.FetchResult;
import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.BlocklistSourceDto;
import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import cc.hrva.urlshortener.exception.BlocklistSourceNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.blocklist.BlocklistConfigurationChangedEvent;
import cc.hrva.urlshortener.service.blocklist.BlocklistMemoryGuard;
import cc.hrva.urlshortener.service.blocklist.BlocklistParser;
import cc.hrva.urlshortener.service.blocklist.BlocklistRefresher;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.service.blocklist.LinkListParser;
import cc.hrva.urlshortener.service.impl.DefaultBlocklistSourceService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.RedisConnectionFailureException;

import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultBlocklistSourceServiceTest {

    private static final String MAIN = "https://hagezi-mirror.dnsbunker.org/wildcard/fake-onlydomains.txt";
    private static final String FALLBACK = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/fake-onlydomains.txt";
    private static final String SECOND_FALLBACK = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/fake-onlydomains.txt";
    private static final String OTHER = "https://hagezi-mirror.dnsbunker.org/wildcard/dyndns-onlydomains.txt";
    private static final String OPENPHISH = "https://raw.githubusercontent.com/openphish/public_feed/refs/heads/main/feed.txt";
    private static final String ETAG = "W/\"6ad7ef4a-499d4\"";
    private static final String NEW_ETAG = "W/\"6ad8a1c0-49b12\"";
    private static final String LAST_MODIFIED = "Tue, 21 Oct 2026 07:45:12 GMT";
    private static final String VERSION = "2026.1021.0745.12";
    private static final String LIST = """
            # Title: HaGeZi's Fake DNS Blocklist
            # Version: 2026.1021.0745.12
            # Number of entries: 3
            *.fake-shop.example
            *.njuskaio.cam
            *.jofogas.cards
            """;
    private static final Set<String> LIST_DOMAINS = Set.of("fake-shop.example", "njuskaio.cam", "jofogas.cards");
    private static final String LIST_WITHOUT_A_COUNT = """
            # Title: HaGeZi's Fake DNS Blocklist
            *.fake-shop.example
            *.njuskaio.cam
            *.jofogas.cards
            """;
    private static final String SHRUNK = "The new download has 3 entries, less than half of the 17,512 loaded before. Kept the old list.";
    private static final String ERROR_PAGE = """
            <html>
            <head><title>502 Bad Gateway</title></head>
            <body>
            <center><h1>502 Bad Gateway</h1></center>
            <hr><center>nginx</center>
            </body>
            </html>
            """;
    private static final String NO_DOMAINS = "No domains found. Use the list's domains, hosts or adblock format.";
    private static final String NO_LINKS = "No links found. Use a list with one link per line or a CSV with a url column.";
    private static final long MEGABYTE = 1024L * 1024L;

    @TempDir
    private Path downloads;

    @Mock
    private BlocklistSourceRepository blocklistSourceRepository;

    @Mock
    private BlocklistStore blocklistStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ApplicationEventPublisher alertPublisher;

    private final Map<Long, BlocklistSource> rows = new HashMap<>();
    private FakeFetcher fetcher;
    private BlocklistSourceService blocklistSourceService;

    @BeforeEach
    void setUp() {
        fetcher = new FakeFetcher(downloads);
        final var memoryGuard = new BlocklistMemoryGuard(blocklistStore, blocklistSourceRepository, new AppProperties());
        final var refresher = new BlocklistRefresher(blocklistSourceRepository, blocklistStore, memoryGuard, fetcher,
                new BlocklistParser(), new LinkListParser(), alertPublisher);
        blocklistSourceService = new DefaultBlocklistSourceService(blocklistSourceRepository, blocklistStore, refresher, eventPublisher);
        lenient().when(blocklistSourceRepository.save(any(BlocklistSource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(blocklistSourceRepository.recordRefresh(anyLong(), any(), any(), any())).thenAnswer(invocation ->
                recordedOnTheRow(invocation.getArgument(0), invocation.getArgument(1), row -> {
                    row.setLastFetchedAt(invocation.getArgument(2));
                    row.setLastError(invocation.getArgument(3));
                }));
        lenient().when(blocklistSourceRepository.recordLoadedList(anyLong(), any(), any(), any(), any(), anyLong(), any(), any(), any()))
                .thenAnswer(invocation -> recordedOnTheRow(invocation.getArgument(0), invocation.getArgument(1), row -> {
                    row.setLastFetchedAt(invocation.getArgument(2));
                    row.setLastError(invocation.getArgument(3));
                    row.setLastSuccessAt(invocation.getArgument(2));
                    row.setLastSuccessUrl(invocation.getArgument(4));
                    row.setDomainCount(invocation.getArgument(5));
                    row.setListVersion(invocation.getArgument(6));
                    row.setEtag(invocation.getArgument(7));
                    row.setLastModified(invocation.getArgument(8));
                }));
    }

    @Test
    void aRefreshThatGetsAnHtmlPageKeepsTheSetAndRecordsTheError() {
        final var lastSuccessAt = LocalDateTime.now().minusHours(6);
        final var source = stored(source(1).lastSuccessAt(lastSuccessAt).domainCount(17_512L).build());
        fetcher.serves(MAIN, ERROR_PAGE);

        final var response = blocklistSourceService.refresh(1);

        assertThat(response.lastError()).isEqualTo(NO_DOMAINS);
        assertThat(source.getLastSuccessAt()).isEqualTo(lastSuccessAt);
        assertThat(source.getDomainCount()).isEqualTo(17_512L);
        assertThat(source.getLastFetchedAt()).isCloseTo(LocalDateTime.now(), within(5, SECONDS));
        verify(blocklistStore, never()).replace(anyLong(), anySet());
        verify(blocklistStore, never()).remove(anyLong());
    }

    @Test
    void aSuccessfulRefreshReplacesTheSetAndRecordsTheList() {
        final var source = stored(source(1).lastError("main: HTTP 503").build());
        fetcher.serves(MAIN, LIST, ETAG, LAST_MODIFIED);
        redisHasRoom();
        when(blocklistStore.memoryBytes(1)).thenReturn(1_234_567L);

        final var response = blocklistSourceService.refresh(1);

        verify(blocklistStore).replace(1, LIST_DOMAINS);
        assertThat(source.getDomainCount()).isEqualTo(3L);
        assertThat(source.getLastSuccessAt()).isCloseTo(LocalDateTime.now(), within(5, SECONDS));
        assertThat(source.getLastFetchedAt()).isCloseTo(LocalDateTime.now(), within(5, SECONDS));
        assertThat(source.getListVersion()).isEqualTo(VERSION);
        assertThat(source.getEtag()).isEqualTo(ETAG);
        assertThat(source.getLastModified()).isEqualTo(LAST_MODIFIED);
        assertThat(source.getLastSuccessUrl()).isEqualTo(MAIN);
        assertThat(source.getLastError()).isNull();
        verify(blocklistSourceRepository, never()).save(any());
        verify(blocklistSourceRepository).recordLoadedList(eq(1L), eq(SourceKind.DOMAINS), any(), isNull(), eq(MAIN), eq(3L), eq(VERSION),
                eq(ETAG), eq(LAST_MODIFIED));
        assertThat(response.listVersion()).isEqualTo(VERSION);
        assertThat(response.domainCount()).isEqualTo(3L);
        assertThat(response.memoryBytes()).isEqualTo(1_234_567L);
        assertThat(fetcher.requests).containsExactly(new Request(MAIN, null, null, 100L * MEGABYTE, Duration.ofSeconds(60)));
    }

    @Test
    void validatorsTooLongToStoreAreDroppedRatherThanFailingTheRefresh() {
        final var source = stored(source(1).build());
        fetcher.serves(MAIN, LIST, "W/\"" + "a".repeat(300) + "\"", "Tue, 21 Oct 2026 07:45:12 GMT" + " ".repeat(100));
        redisHasRoom();

        blocklistSourceService.refresh(1);

        verify(blocklistStore).replace(1, LIST_DOMAINS);
        assertThat(source.getEtag()).isNull();
        assertThat(source.getLastModified()).isNull();
        assertThat(source.getLastSuccessUrl()).isEqualTo(MAIN);
        assertThat(source.getLastError()).isNull();
    }

    @Test
    void aNotModifiedAnswerOnlyUpdatesLastFetchedAt() {
        final var lastFetchedAt = LocalDateTime.now().minusHours(6);
        final var lastSuccessAt = LocalDateTime.now().minusHours(12);
        final var source = stored(source(1)
                .lastSuccessUrl(MAIN).etag(ETAG).lastModified(LAST_MODIFIED)
                .lastFetchedAt(lastFetchedAt).lastSuccessAt(lastSuccessAt).domainCount(3L).listVersion(VERSION)
                .build());
        when(blocklistStore.exists(1)).thenReturn(true);
        fetcher.notModified(MAIN);

        blocklistSourceService.refresh(1);

        assertThat(source.getLastFetchedAt()).isCloseTo(LocalDateTime.now(), within(5, SECONDS));
        assertThat(source)
                .extracting(BlocklistSource::getLastSuccessAt, BlocklistSource::getDomainCount, BlocklistSource::getListVersion,
                        BlocklistSource::getEtag, BlocklistSource::getLastModified, BlocklistSource::getLastSuccessUrl,
                        BlocklistSource::getLastError)
                .containsExactly(lastSuccessAt, 3L, VERSION, ETAG, LAST_MODIFIED, MAIN, null);
        assertThat(fetcher.requests).extracting(Request::etag, Request::lastModified).containsExactly(tuple(ETAG, LAST_MODIFIED));
        verify(blocklistStore, never()).replace(anyLong(), anySet());
    }

    @Test
    void aNotModifiedMainUrlClearsTheLastError() {
        final var source = stored(source(1).lastSuccessUrl(MAIN).etag(ETAG).lastError("main: HTTP 503").build());
        when(blocklistStore.exists(1)).thenReturn(true);
        fetcher.notModified(MAIN);

        blocklistSourceService.refresh(1);

        assertThat(source.getLastError()).isNull();
    }

    @Test
    void disablingASourceRemovesItsSet() {
        final var source = stored(source(1).build());

        blocklistSourceService.update(1, new BlocklistSourceDto("HaGeZi Fake", MAIN, null, BlocklistAction.BLOCK, false, null, null));

        assertThat(source.isEnabled()).isFalse();
        verify(blocklistStore).remove(1);
    }

    @Test
    void deletingASourceRemovesItsSet() {
        final var source = stored(source(1).build());

        blocklistSourceService.delete(1);

        verify(blocklistSourceRepository).delete(source);
        verify(blocklistStore).remove(1);
    }

    @Test
    void creatingUpdatingOrDeletingASourceTellsTheClickCheckToReload() {
        stored(source(1).build());

        blocklistSourceService.create(new BlocklistSourceDto("Phishing Army", OTHER, null, BlocklistAction.BLOCK, null, null, null));
        blocklistSourceService.update(1, new BlocklistSourceDto("HaGeZi Fake", MAIN, null, BlocklistAction.BLOCK, false, null, null));
        blocklistSourceService.delete(1);

        verify(eventPublisher, times(3)).publishEvent(any(BlocklistConfigurationChangedEvent.class));
    }

    @Test
    void anInvalidOrUnknownSourceChangeTellsTheClickCheckNothing() {
        stored(source(1).build());

        assertThatThrownBy(() -> blocklistSourceService.update(1,
                new BlocklistSourceDto("HaGeZi Fake", "http://insecure.example/list.txt", null, BlocklistAction.BLOCK, false, null, null)))
                .isInstanceOf(UrlValidationException.class);
        assertThatThrownBy(() -> blocklistSourceService.delete(99)).isInstanceOf(BlocklistSourceNotFoundException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void startupReloadsOnlyEnabledSourcesWhoseSetIsMissing() {
        final var loaded = source(1).build();
        final var missing = stored(source(2).url(OTHER).build());
        final var disabled = source(3).url(SECOND_FALLBACK).enabled(false).build();
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(loaded, missing));
        lenient().when(blocklistSourceRepository.findAll()).thenReturn(List.of(loaded, missing, disabled));
        when(blocklistStore.exists(1)).thenReturn(true);
        when(blocklistStore.exists(2)).thenReturn(false);
        fetcher.serves(OTHER, LIST);
        redisHasRoom();

        blocklistSourceService.reloadMissing();

        assertThat(fetcher.requestedUrls()).containsExactly(OTHER);
        verify(blocklistStore).replace(2, LIST_DOMAINS);
    }

    @Test
    void whileRedisIsDownTheMissingCheckStopsAtTheFirstFailedLookupAndDownloadsNothing() {
        final var first = source(1).build();
        final var second = source(2).url(OTHER).build();
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(first, second));
        when(blocklistStore.exists(1)).thenThrow(new RedisConnectionFailureException("Connection refused"));

        blocklistSourceService.reloadMissing();

        verify(blocklistStore, times(1)).exists(anyLong());
        assertThat(fetcher.requestedUrls()).isEmpty();
        verify(blocklistStore, never()).replace(anyLong(), anySet());
    }

    @Test
    void theHourlyRunRefreshesSourcesPastTheirIntervalAndSkipsTheRest() {
        final var now = LocalDateTime.now();
        final var overdue = stored(source(1).refreshIntervalHours(6).lastFetchedAt(now.minusHours(7)).build());
        final var recent = source(2).url(OTHER).refreshIntervalHours(6).lastFetchedAt(now.minusHours(5)).build();
        final var neverFetched = stored(source(3).url(OPENPHISH).kind(SourceKind.LINKS).build());
        final var disabled = source(4).url(SECOND_FALLBACK).enabled(false).lastFetchedAt(now.minusDays(3)).build();
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(overdue, recent, neverFetched));
        lenient().when(blocklistSourceRepository.findAll()).thenReturn(List.of(overdue, recent, neverFetched, disabled));
        when(blocklistStore.exists(2)).thenReturn(true);
        fetcher.serves(MAIN, LIST);
        fetcher.serves(OPENPHISH, "https://github.com/x/y/raw/evil.exe\n");
        redisHasRoom();

        blocklistSourceService.refreshDue();

        assertThat(fetcher.requestedUrls()).containsExactly(MAIN, OPENPHISH);
    }

    @Test
    void theHourlyRunReloadsARecentlyFetchedSourceWhoseSetIsMissing() {
        final var flushed = stored(source(1).lastFetchedAt(LocalDateTime.now().minusMinutes(30)).build());
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(flushed));
        when(blocklistStore.exists(1)).thenReturn(false);
        fetcher.serves(MAIN, LIST);
        redisHasRoom();

        blocklistSourceService.refreshDue();

        verify(blocklistStore).replace(1, LIST_DOMAINS);
    }

    @Test
    void aSourceFetchedJustAfterTheLastHourlyRunIsDueOneIntervalLater() {
        final var hourly = stored(source(1).refreshIntervalHours(1).lastFetchedAt(LocalDateTime.now().minusMinutes(58)).build());
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(hourly));
        lenient().when(blocklistStore.exists(1)).thenReturn(true);
        fetcher.serves(MAIN, LIST);
        redisHasRoom();

        blocklistSourceService.refreshDue();

        assertThat(fetcher.requestedUrls()).containsExactly(MAIN);
    }

    @Test
    void oneSourceFailingDoesNotStopTheOthers() {
        final var broken = source(1).build();
        final var healthy = stored(source(2).url(OTHER).build());
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(broken, healthy));
        when(blocklistSourceRepository.findById(1L)).thenThrow(new DataAccessResourceFailureException("connection reset"));
        fetcher.serves(MAIN, LIST);
        fetcher.serves(OTHER, LIST);
        redisHasRoom();

        blocklistSourceService.refreshAll();

        verify(blocklistStore).replace(2, LIST_DOMAINS);
        verify(blocklistStore, never()).replace(eq(1L), anySet());
    }

    @Test
    void aScheduledRefreshThatShrinksABigListToLessThanHalfKeepsTheOldSet() {
        final var lastSuccessAt = LocalDateTime.now().minusHours(7);
        final var source = stored(source(1).lastFetchedAt(lastSuccessAt).lastSuccessAt(lastSuccessAt).domainCount(17_512L).build());
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
        when(blocklistStore.exists(1)).thenReturn(true);
        fetcher.serves(MAIN, LIST_WITHOUT_A_COUNT);

        blocklistSourceService.refreshDue();

        verify(blocklistStore, never()).replace(anyLong(), anySet());
        assertThat(source.getLastError()).isEqualTo(SHRUNK);
        assertThat(source.getDomainCount()).isEqualTo(17_512L);
        assertThat(source.getLastSuccessAt()).isEqualTo(lastSuccessAt);
        assertThat(source.getLastFetchedAt()).isCloseTo(LocalDateTime.now(), within(5, SECONDS));
    }

    @Test
    void refreshNowAcceptsABigListThatReallyShrank() {
        final var source = stored(source(1).domainCount(17_512L).build());
        lenient().when(blocklistStore.exists(1)).thenReturn(true);
        fetcher.serves(MAIN, LIST_WITHOUT_A_COUNT);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        verify(blocklistStore).replace(1, LIST_DOMAINS);
        assertThat(source.getDomainCount()).isEqualTo(3L);
        assertThat(source.getLastError()).isNull();
    }

    @Test
    void aScheduledRefreshKeepingHalfOfABigListReplacesTheSet() {
        final var source = stored(source(1).domainCount(2_000L).build());
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
        fetcher.serves(MAIN, domains(1_000));
        redisHasRoom();

        blocklistSourceService.refreshAll();

        verify(blocklistStore).replace(eq(1L), anySet());
        assertThat(source.getDomainCount()).isEqualTo(1_000L);
        assertThat(source.getLastError()).isNull();
    }

    @Test
    void aScheduledRefreshMayShrinkAListOfFewerThanAThousandEntries() {
        final var source = stored(source(1).domainCount(999L).build());
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
        fetcher.serves(MAIN, LIST_WITHOUT_A_COUNT);
        redisHasRoom();

        blocklistSourceService.refreshAll();

        verify(blocklistStore).replace(1, LIST_DOMAINS);
        assertThat(source.getLastError()).isNull();
    }

    @Test
    void aScheduledRefreshOfAListThatDeclaresItsCountIsJudgedByThatCount() {
        final var source = stored(source(1).domainCount(17_512L).build());
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
        fetcher.serves(MAIN, LIST);
        redisHasRoom();

        blocklistSourceService.refreshAll();

        verify(blocklistStore).replace(1, LIST_DOMAINS);
        assertThat(source.getLastError()).isNull();
    }

    @Test
    void aScheduledReloadOfAMissingSetTakesAShrunkenListBecauseThereIsNoOldSetToKeep() {
        final var source = stored(source(1).domainCount(17_512L).build());
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
        when(blocklistStore.exists(1)).thenReturn(false);
        fetcher.serves(MAIN, LIST_WITHOUT_A_COUNT);
        redisHasRoom();

        blocklistSourceService.reloadMissing();

        verify(blocklistStore).replace(1, LIST_DOMAINS);
        assertThat(source.getDomainCount()).isEqualTo(3L);
        assertThat(source.getLastError()).isNull();
    }

    @Test
    void whileRedisCannotSayWhetherTheOldSetIsThereAShrunkenListIsStillRefused() {
        final var source = stored(source(1).domainCount(17_512L).build());
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
        when(blocklistStore.exists(1)).thenThrow(new RedisConnectionFailureException("Connection refused"));
        fetcher.serves(MAIN, LIST_WITHOUT_A_COUNT);

        blocklistSourceService.refreshAll();

        verify(blocklistStore, never()).replace(anyLong(), anySet());
        assertThat(source.getLastError()).isEqualTo(SHRUNK);
    }

    @Test
    void aShrunkenDownloadFromTheMainUrlFallsBackToAMirror() {
        final var source = stored(source(1).fallbackUrls(List.of(FALLBACK)).domainCount(1_000L).build());
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
        when(blocklistStore.exists(1)).thenReturn(true);
        fetcher.serves(MAIN, LIST_WITHOUT_A_COUNT);
        fetcher.serves(FALLBACK, domains(1_000));
        redisHasRoom();

        blocklistSourceService.refreshAll();

        verify(blocklistStore).replace(eq(1L), anySet());
        assertThat(source.getLastSuccessUrl()).isEqualTo(FALLBACK);
        assertThat(source.getDomainCount()).isEqualTo(1_000L);
        assertThat(source.getLastError())
                .isEqualTo("main: The new download has 3 entries, less than half of the 1,000 loaded before. Kept the old list.");
    }

    @Test
    void aFallbackIsUsedWhenTheMainUrlFails() {
        final var source = stored(source(1).fallbackUrls(List.of(FALLBACK)).lastSuccessUrl(MAIN).build());
        fetcher.fails(MAIN, "HTTP 503");
        fetcher.serves(FALLBACK, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        verify(blocklistStore).replace(1, LIST_DOMAINS);
        assertThat(source.getLastSuccessUrl()).isEqualTo(FALLBACK);
        assertThat(source.getLastSuccessAt()).isCloseTo(LocalDateTime.now(), within(5, SECONDS));
        assertThat(source.getLastError()).isEqualTo("main: HTTP 503");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailedDownloadIsOneWarnLineWithTheSourceAttemptAndReasonButNoStackTrace(final CapturedOutput output) {
        stored(source(1).fallbackUrls(List.of(FALLBACK)).lastSuccessUrl(MAIN).build());
        fetcher.fails(MAIN, "HTTP 503");
        fetcher.serves(FALLBACK, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        assertThat(output)
                .contains("Blocklist source HaGeZi Fake could not be loaded (main): HTTP 503")
                .doesNotContain("UrlValidationException")
                .doesNotContain("\tat ");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void downloadLogLinesNameTheSourceAndTheAttemptButNeverTheUrlWhichMayCarryAKey(final CapturedOutput output) {
        final var keyed = "https://urlhaus.abuse.ch/downloads/text_online/?auth-key=0123456789abcdef";
        final var keyedFallback = "https://mirror.example/urlhaus.txt?key=fedcba9876543210";
        final var source = stored(source(1).url(keyed).fallbackUrls(List.of(keyedFallback)).build());
        fetcher.fails(keyed, "HTTP 503");
        fetcher.serves(keyedFallback, LIST, ETAG, LAST_MODIFIED);
        redisHasRoom();
        blocklistSourceService.refresh(1);
        when(blocklistStore.exists(1)).thenReturn(true);
        fetcher.notModified(keyedFallback);

        blocklistSourceService.refresh(1);

        assertThat(source.getLastSuccessUrl()).isEqualTo(keyedFallback);
        assertThat(output)
                .contains("Blocklist source HaGeZi Fake could not be loaded (main): HTTP 503")
                .contains("Blocklist source HaGeZi Fake loaded 3 entries (fallback 1)")
                .contains("Blocklist source HaGeZi Fake is unchanged (fallback 1)")
                .doesNotContain("urlhaus.abuse.ch")
                .doesNotContain("mirror.example")
                .doesNotContain("0123456789abcdef")
                .doesNotContain("fedcba9876543210");
    }

    @Test
    void aFallbackIsUsedWhenTheMainUrlServesAnHtmlPage() {
        final var source = stored(source(1).fallbackUrls(List.of(FALLBACK)).build());
        fetcher.serves(MAIN, ERROR_PAGE);
        fetcher.serves(FALLBACK, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        verify(blocklistStore).replace(1, LIST_DOMAINS);
        assertThat(source.getLastSuccessUrl()).isEqualTo(FALLBACK);
        assertThat(source.getLastError()).isEqualTo("main: " + NO_DOMAINS);
    }

    @Test
    void whenEveryUrlFailsTheSetAndItsOriginStayAsTheyWere() {
        final var lastSuccessAt = LocalDateTime.now().minusHours(6);
        final var source = stored(source(1)
                .fallbackUrls(List.of(FALLBACK, SECOND_FALLBACK))
                .lastSuccessUrl(FALLBACK).lastSuccessAt(lastSuccessAt).domainCount(3L)
                .build());
        fetcher.fails(MAIN, "HTTP 503");
        fetcher.fails(FALLBACK, "timed out");
        fetcher.serves(SECOND_FALLBACK, ERROR_PAGE);

        blocklistSourceService.refresh(1);

        verify(blocklistStore, never()).replace(anyLong(), anySet());
        assertThat(fetcher.requestedUrls()).containsExactly(MAIN, FALLBACK, SECOND_FALLBACK);
        assertThat(source.getLastError()).isEqualTo("main: HTTP 503; fallback 1: timed out; fallback 2: " + NO_DOMAINS);
        assertThat(source.getLastSuccessUrl()).isEqualTo(FALLBACK);
        assertThat(source.getLastSuccessAt()).isEqualTo(lastSuccessAt);
        assertThat(source.getDomainCount()).isEqualTo(3L);
        assertThat(source.getLastFetchedAt()).isCloseTo(LocalDateTime.now(), within(5, SECONDS));
    }

    @Test
    void validatorsAreSentOnlyToTheUrlTheSetCameFrom() {
        final var source = stored(source(1)
                .fallbackUrls(List.of(FALLBACK))
                .lastSuccessUrl(FALLBACK).etag(ETAG).lastModified(LAST_MODIFIED)
                .build());
        when(blocklistStore.exists(1)).thenReturn(true);
        fetcher.fails(MAIN, "HTTP 503");
        fetcher.notModified(FALLBACK);

        blocklistSourceService.refresh(1);

        assertThat(fetcher.requests).extracting(Request::url, Request::etag, Request::lastModified)
                .containsExactly(tuple(MAIN, null, null), tuple(FALLBACK, ETAG, LAST_MODIFIED));
        assertThat(source.getLastError()).isEqualTo("main: HTTP 503");
        verify(blocklistStore, never()).replace(anyLong(), anySet());
    }

    @Test
    void aMirrorOtherThanTheOneTheSetCameFromIsAskedUnconditionally() {
        final var source = stored(source(1)
                .fallbackUrls(List.of(FALLBACK))
                .lastSuccessUrl(MAIN).etag(ETAG).lastModified(LAST_MODIFIED)
                .build());
        when(blocklistStore.exists(1)).thenReturn(true);
        fetcher.fails(MAIN, "HTTP 503");
        fetcher.serves(FALLBACK, LIST, NEW_ETAG, null);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        assertThat(fetcher.requests).extracting(Request::url, Request::etag, Request::lastModified)
                .containsExactly(tuple(MAIN, ETAG, LAST_MODIFIED), tuple(FALLBACK, null, null));
        assertThat(source.getLastSuccessUrl()).isEqualTo(FALLBACK);
        assertThat(source.getEtag()).isEqualTo(NEW_ETAG);
        assertThat(source.getLastModified()).isNull();
    }

    @Test
    void aMissingSetIsDownloadedInFullEvenFromTheUrlItCameFrom() {
        stored(source(1).lastSuccessUrl(MAIN).etag(ETAG).lastModified(LAST_MODIFIED).build());
        when(blocklistStore.exists(1)).thenReturn(false);
        fetcher.serves(MAIN, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        assertThat(fetcher.requests).extracting(Request::etag, Request::lastModified).containsExactly(tuple(null, null));
        verify(blocklistStore).replace(1, LIST_DOMAINS);
    }

    @Test
    void aLinksSourceIsReadAsExactLinks() {
        stored(source(1).name("OpenPhish").url(OPENPHISH).kind(SourceKind.LINKS).build());
        fetcher.serves(OPENPHISH, """
                https://github.com/x/y/raw/evil.exe
                http://login.example-bank.top/verify?id=1
                """);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        verify(blocklistStore).replace(1, Set.of("github.com/x/y/raw/evil.exe", "login.example-bank.top/verify?id=1"));
    }

    @Test
    void aDomainsSourceNeverReadsFullLinks() {
        final var source = stored(source(1).build());
        fetcher.serves(MAIN, "https://github.com/x/y/raw/evil.exe\n");

        blocklistSourceService.refresh(1);

        assertThat(source.getLastError()).isEqualTo(NO_DOMAINS);
        verify(blocklistStore, never()).replace(anyLong(), anySet());
    }

    @Test
    void aLinksListWithoutLinksSaysWhichFormatsWork() {
        final var source = stored(source(1).url(OPENPHISH).kind(SourceKind.LINKS).build());
        fetcher.serves(OPENPHISH, ERROR_PAGE);

        blocklistSourceService.refresh(1);

        assertThat(source.getLastError()).isEqualTo(NO_LINKS);
    }

    @Test
    void aLoadThatWouldTakeRedisPastEightyPercentOfMaxmemoryIsRefused() {
        final var lastSuccessAt = LocalDateTime.now().minusHours(6);
        final var source = stored(source(1).etag(ETAG).lastSuccessAt(lastSuccessAt).domainCount(3L).build());
        fetcher.serves(MAIN, LIST, NEW_ETAG, null);
        when(blocklistStore.memory()).thenReturn(new BlocklistStore.RedisMemory(800 * MEGABYTE - 200, 1000 * MEGABYTE));

        blocklistSourceService.refresh(1);

        verify(blocklistStore, never()).replace(anyLong(), anySet());
        assertThat(source.getLastError()).startsWith("Not loaded").contains("80 %").contains("1000 MB");
        assertThat(source.getEtag()).isEqualTo(ETAG);
        assertThat(source.getLastSuccessAt()).isEqualTo(lastSuccessAt);
        assertThat(source.getLastSuccessUrl()).isNull();
        assertThat(source.getDomainCount()).isEqualTo(3L);
    }

    @Test
    void theSetBeingReplacedCountsAsFreedUnderMaxmemory() {
        stored(source(1).build());
        fetcher.serves(MAIN, LIST);
        when(blocklistStore.memory()).thenReturn(new BlocklistStore.RedisMemory(800 * MEGABYTE - 200, 1000 * MEGABYTE));
        when(blocklistStore.memoryBytes(1)).thenReturn(150L);

        blocklistSourceService.refresh(1);

        verify(blocklistStore).replace(1, LIST_DOMAINS);
    }

    @Test
    void withoutMaxmemoryALoadThatWouldTakeAllListsPastTheBudgetIsRefused() {
        final var source = stored(source(1).build());
        final var other = source(2).url(OTHER).build();
        when(blocklistSourceRepository.findAll()).thenReturn(List.of(source, other));
        when(blocklistStore.memory()).thenReturn(new BlocklistStore.RedisMemory(300 * MEGABYTE, 0));
        when(blocklistStore.memoryBytes(2)).thenReturn(256 * MEGABYTE - 200);
        fetcher.serves(MAIN, LIST);

        blocklistSourceService.refresh(1);

        verify(blocklistStore, never()).replace(anyLong(), anySet());
        assertThat(source.getLastError()).startsWith("Not loaded").contains("256 MB");
    }

    @Test
    void withoutMaxmemoryTheSetBeingReplacedIsNotCountedAgainstTheBudget() {
        final var source = stored(source(1).build());
        final var other = source(2).url(OTHER).build();
        when(blocklistSourceRepository.findAll()).thenReturn(List.of(source, other));
        when(blocklistStore.memory()).thenReturn(new BlocklistStore.RedisMemory(300 * MEGABYTE, 0));
        when(blocklistStore.memoryBytes(1)).thenReturn(200 * MEGABYTE);
        when(blocklistStore.memoryBytes(2)).thenReturn(56 * MEGABYTE - 300);
        fetcher.serves(MAIN, LIST);

        blocklistSourceService.refresh(1);

        verify(blocklistStore).replace(1, LIST_DOMAINS);
    }

    @Test
    void linksAreEstimatedAtTwoHundredBytesEachAndDomainsAtOneHundred() {
        final var domains = stored(source(1).build());
        final var links = stored(source(2).url(OPENPHISH).kind(SourceKind.LINKS).build());
        final var other = source(3).url(OTHER).build();
        when(blocklistSourceRepository.findAll()).thenReturn(List.of(domains, links, other));
        when(blocklistStore.memory()).thenReturn(new BlocklistStore.RedisMemory(300 * MEGABYTE, 0));
        when(blocklistStore.memoryBytes(anyLong())).thenAnswer(invocation -> invocation.<Long>getArgument(0) == 3L ? 256 * MEGABYTE - 500 : 0L);
        fetcher.serves(MAIN, LIST);
        fetcher.serves(OPENPHISH, """
                https://a.example/1
                https://a.example/2
                https://a.example/3
                """);

        blocklistSourceService.refresh(1);
        blocklistSourceService.refresh(2);

        verify(blocklistStore).replace(1, LIST_DOMAINS);
        verify(blocklistStore, never()).replace(eq(2L), anySet());
        assertThat(links.getLastError()).startsWith("Not loaded");
    }

    @Test
    void aRedisFailureWhileStoringKeepsTheOldSetAndItsValidators() {
        final var source = stored(source(1).etag(ETAG).build());
        fetcher.serves(MAIN, LIST, NEW_ETAG, null);
        redisHasRoom();
        doThrow(new RedisConnectionFailureException("Unable to connect to Redis")).when(blocklistStore).replace(eq(1L), anySet());

        blocklistSourceService.refresh(1);

        assertThat(source.getLastError()).isEqualTo("could not store the list in Redis");
        assertThat(source.getEtag()).isEqualTo(ETAG);
        assertThat(source.getLastSuccessUrl()).isNull();
        assertThat(source.getLastSuccessAt()).isNull();
    }

    @Test
    void anUnreadableDownloadCountsAsAFailedAttempt() {
        final var source = stored(source(1).fallbackUrls(List.of(FALLBACK)).build());
        fetcher.servesAMissingFile(MAIN);
        fetcher.serves(FALLBACK, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        assertThat(source.getLastError()).isEqualTo("main: could not read the download");
        assertThat(source.getLastSuccessUrl()).isEqualTo(FALLBACK);
        verify(blocklistStore).replace(1, LIST_DOMAINS);
    }

    @Test
    void anUnexpectedErrorCountsAsAFailedAttempt() {
        final var source = stored(source(1).fallbackUrls(List.of(FALLBACK)).build());
        fetcher.throwsOn(MAIN, new IllegalStateException("connection pool shut down"));
        fetcher.serves(FALLBACK, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        assertThat(source.getLastError()).isEqualTo("main: unexpected error");
        verify(blocklistStore).replace(1, LIST_DOMAINS);
    }

    @Test
    void everyDownloadedFileIsDeleted() {
        stored(source(1).fallbackUrls(List.of(FALLBACK)).build());
        fetcher.serves(MAIN, ERROR_PAGE);
        fetcher.serves(FALLBACK, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        assertThat(fetcher.bodies).hasSize(2).noneMatch(Files::exists);
    }

    @Test
    void aRefreshAskedForWhileTheSameSourceIsRefreshingReturnsTheCurrentStateWithoutFetching() {
        stored(source(1).lastError("main: HTTP 503").build());
        final var concurrent = new AtomicReference<BlocklistSourceResponse>();
        fetcher.serves(MAIN, LIST, () -> concurrent.set(blocklistSourceService.refresh(1)));
        redisHasRoom();

        blocklistSourceService.refresh(1);

        assertThat(fetcher.requestedUrls()).containsExactly(MAIN);
        assertThat(concurrent.get().lastError()).isEqualTo("main: HTTP 503");
        verify(blocklistStore, times(1)).replace(1, LIST_DOMAINS);
    }

    @Test
    void aSourceIsRefreshedAgainOnceItsLastRefreshEnded() {
        stored(source(1).build());
        fetcher.serves(MAIN, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);
        blocklistSourceService.refresh(1);

        assertThat(fetcher.requestedUrls()).containsExactly(MAIN, MAIN);
    }

    @Test
    void aSourceDisabledDuringTheDownloadIsNotLoaded() {
        final var source = stored(source(1).build());
        fetcher.serves(MAIN, LIST, () -> source.setEnabled(false));

        blocklistSourceService.refresh(1);

        verify(blocklistStore, never()).replace(anyLong(), anySet());
        verifyNoRefreshRecorded();
    }

    @Test
    void aSourceWhoseKindChangedDuringTheDownloadIsNotLoaded() {
        final var source = stored(source(1).build());
        fetcher.serves(MAIN, LIST, () -> source.setKind(SourceKind.LINKS));

        blocklistSourceService.refresh(1);

        verify(blocklistStore, never()).replace(anyLong(), anySet());
        verifyNoRefreshRecorded();
    }

    @Test
    void aSourceDisabledWhileItsSetIsStoredLosesTheNewSetEvenWhenRedisFailsToRemoveIt() {
        final var source = stored(source(1).build());
        fetcher.serves(MAIN, LIST);
        redisHasRoom();
        doAnswer(invocation -> {
            source.setEnabled(false);
            return null;
        }).when(blocklistStore).replace(1, LIST_DOMAINS);
        doThrow(new RedisConnectionFailureException("Connection refused")).when(blocklistStore).remove(1);

        final var response = blocklistSourceService.refresh(1);

        verify(blocklistStore).remove(1);
        assertThat(response.enabled()).isFalse();
        assertThat(source.getDomainCount()).isNull();
        assertThat(source.getLastFetchedAt()).isNull();
        verifyNoInteractions(alertPublisher);
    }

    @Test
    void aSourceDeletedDuringTheDownloadIsNotLoaded() {
        when(blocklistSourceRepository.findById(1L)).thenReturn(Optional.of(source(1).build()), Optional.empty());
        fetcher.serves(MAIN, LIST);

        blocklistSourceService.refresh(1);

        verify(blocklistStore, never()).replace(anyLong(), anySet());
        verifyNoRefreshRecorded();
    }

    @Test
    void refreshingAnUnknownSourceIsNotFound() {
        assertThatThrownBy(() -> blocklistSourceService.refresh(99)).isInstanceOf(BlocklistSourceNotFoundException.class);
    }

    @Test
    void aSourceWithAnHttpUrlIsRefused() {
        assertRefused(new BlocklistSourceDto("Plain", "http://lists.example/domains.txt", null, BlocklistAction.BLOCK, null, null, null));
    }

    @Test
    void aSourceWithAMalformedUrlIsRefused() {
        assertRefused(new BlocklistSourceDto("Broken", "https://lists example/domains.txt", null, BlocklistAction.BLOCK, null, null, null));
        assertRefused(new BlocklistSourceDto("No host", "https:///domains.txt", null, BlocklistAction.BLOCK, null, null, null));
        assertRefused(new BlocklistSourceDto("Missing", null, null, BlocklistAction.BLOCK, null, null, null));
    }

    @Test
    void moreThanThreeFallbackUrlsAreRefused() {
        assertRefused(new BlocklistSourceDto("Mirrored", MAIN, null, BlocklistAction.BLOCK, null, null,
                List.of(FALLBACK, SECOND_FALLBACK, OTHER, OPENPHISH)));
    }

    @Test
    void anHttpFallbackUrlIsRefused() {
        assertRefused(new BlocklistSourceDto("Mirrored", MAIN, null, BlocklistAction.BLOCK, null, null,
                List.of(FALLBACK, "http://mirror.example/fake-onlydomains.txt")));
    }

    @Test
    void theRefreshIntervalMustBeBetweenOneAndOneHundredSixtyEightHours() {
        assertRefused(new BlocklistSourceDto("Hourly", MAIN, null, BlocklistAction.BLOCK, null, 0, null));
        assertRefused(new BlocklistSourceDto("Weekly", MAIN, null, BlocklistAction.BLOCK, null, 169, null));

        blocklistSourceService.create(new BlocklistSourceDto("Hourly", MAIN, null, BlocklistAction.BLOCK, null, 1, null));
        blocklistSourceService.create(new BlocklistSourceDto("Weekly", OTHER, null, BlocklistAction.BLOCK, null, 168, null));

        verify(blocklistSourceRepository, times(2)).save(any(BlocklistSource.class));
    }

    @Test
    void aNameAndAnActionAreRequired() {
        assertRefused(new BlocklistSourceDto(" ", MAIN, null, BlocklistAction.BLOCK, null, null, null));
        assertRefused(new BlocklistSourceDto(null, MAIN, null, BlocklistAction.BLOCK, null, null, null));
        assertRefused(new BlocklistSourceDto("No action", MAIN, null, null, null, null, null));
    }

    @Test
    void aNewSourceDefaultsToAnEnabledDomainListRefreshedEverySixHours() {
        final var response = blocklistSourceService.create(
                new BlocklistSourceDto(" Phishing Army ", " https://phishing.army/download/phishing_army_blocklist.txt ",
                        null, BlocklistAction.FLAG, null, null, List.of(" ", FALLBACK)));

        final var saved = ArgumentCaptor.forClass(BlocklistSource.class);
        verify(blocklistSourceRepository).save(saved.capture());
        assertThat(saved.getValue())
                .extracting(BlocklistSource::getName, BlocklistSource::getUrl, BlocklistSource::getKind, BlocklistSource::getAction,
                        BlocklistSource::isEnabled, BlocklistSource::getRefreshIntervalHours, BlocklistSource::getFallbackUrls)
                .containsExactly("Phishing Army", "https://phishing.army/download/phishing_army_blocklist.txt", SourceKind.DOMAINS,
                        BlocklistAction.FLAG, true, 6, List.of(FALLBACK));
        assertThat(response.name()).isEqualTo("Phishing Army");
        verify(blocklistStore, never()).replace(anyLong(), anySet());
    }

    @Test
    void anUpdateLeavesTheOptionalFieldsItOmitsAsTheyWere() {
        final var source = stored(source(1)
                .kind(SourceKind.LINKS).enabled(true).refreshIntervalHours(24).fallbackUrls(List.of(FALLBACK))
                .build());

        blocklistSourceService.update(1, new BlocklistSourceDto("Renamed", OTHER, null, BlocklistAction.FLAG, null, null, null));

        assertThat(source)
                .extracting(BlocklistSource::getName, BlocklistSource::getUrl, BlocklistSource::getAction, BlocklistSource::getKind,
                        BlocklistSource::isEnabled, BlocklistSource::getRefreshIntervalHours, BlocklistSource::getFallbackUrls)
                .containsExactly("Renamed", OTHER, BlocklistAction.FLAG, SourceKind.LINKS, true, 24, List.of(FALLBACK));
        verify(blocklistStore, never()).remove(anyLong());
    }

    @Test
    void anUpdateWithAnEmptyFallbackListClearsTheFallbacks() {
        final var source = stored(source(1).fallbackUrls(List.of(FALLBACK)).build());

        blocklistSourceService.update(1, new BlocklistSourceDto("HaGeZi Fake", MAIN, null, BlocklistAction.BLOCK, null, null, List.of()));

        assertThat(source.getFallbackUrls()).isEmpty();
    }

    @Test
    void changingTheKindDropsTheSetLoadedUnderTheOldKind() {
        final var source = stored(source(1).build());

        blocklistSourceService.update(1, new BlocklistSourceDto("HaGeZi Fake", MAIN, SourceKind.LINKS, BlocklistAction.BLOCK, null, null, null));

        assertThat(source.getKind()).isEqualTo(SourceKind.LINKS);
        verify(blocklistStore).remove(1);
    }

    @Test
    void changingTheKindForgetsTheStateOfTheListLoadedUnderTheOldKind() {
        final var fetchedAt = LocalDateTime.now().minusHours(1);
        final var source = stored(source(1)
                .lastFetchedAt(fetchedAt).lastSuccessAt(fetchedAt).lastSuccessUrl(MAIN)
                .domainCount(17_512L).listVersion(VERSION).etag(ETAG).lastModified(LAST_MODIFIED)
                .build());

        blocklistSourceService.update(1, new BlocklistSourceDto("HaGeZi Fake", MAIN, SourceKind.LINKS, BlocklistAction.BLOCK, null, null, null));

        assertThat(source)
                .extracting(BlocklistSource::getDomainCount, BlocklistSource::getListVersion, BlocklistSource::getLastSuccessAt,
                        BlocklistSource::getLastSuccessUrl, BlocklistSource::getEtag, BlocklistSource::getLastModified)
                .containsOnlyNulls();
    }

    @Test
    void anUpdateThatKeepsTheKindKeepsTheStateOfTheLoadedList() {
        final var fetchedAt = LocalDateTime.now().minusHours(1);
        final var source = stored(source(1)
                .lastSuccessAt(fetchedAt).lastSuccessUrl(MAIN).domainCount(17_512L).listVersion(VERSION).etag(ETAG)
                .build());

        blocklistSourceService.update(1, new BlocklistSourceDto("Renamed", MAIN, SourceKind.DOMAINS, BlocklistAction.FLAG, null, 12, null));

        assertThat(source)
                .extracting(BlocklistSource::getDomainCount, BlocklistSource::getListVersion, BlocklistSource::getLastSuccessAt,
                        BlocklistSource::getLastSuccessUrl, BlocklistSource::getEtag)
                .containsExactly(17_512L, VERSION, fetchedAt, MAIN, ETAG);
        verify(blocklistStore, never()).remove(anyLong());
    }

    @Test
    void anInvalidUpdateChangesNothing() {
        final var source = stored(source(1).build());

        assertThatThrownBy(() -> blocklistSourceService.update(1,
                new BlocklistSourceDto("HaGeZi Fake", "http://insecure.example/list.txt", null, BlocklistAction.BLOCK, false, null, null)))
                .isInstanceOf(UrlValidationException.class);

        assertThat(source.getUrl()).isEqualTo(MAIN);
        assertThat(source.isEnabled()).isTrue();
        verify(blocklistStore, never()).remove(anyLong());
    }

    @Test
    void updatingOrDeletingAnUnknownSourceIsNotFound() {
        final var dto = new BlocklistSourceDto("HaGeZi Fake", MAIN, null, BlocklistAction.BLOCK, null, null, null);

        assertThatThrownBy(() -> blocklistSourceService.update(99, dto)).isInstanceOf(BlocklistSourceNotFoundException.class);
        assertThatThrownBy(() -> blocklistSourceService.delete(99)).isInstanceOf(BlocklistSourceNotFoundException.class);
        verify(blocklistStore, never()).remove(anyLong());
    }

    @Test
    void theListShowsEverySourceWithItsRedisMemory() {
        final var fetchedAt = LocalDateTime.now().minusHours(1);
        final var source = source(1)
                .fallbackUrls(List.of(FALLBACK)).refreshIntervalHours(6)
                .lastFetchedAt(fetchedAt).lastSuccessAt(fetchedAt).lastSuccessUrl(FALLBACK)
                .domainCount(17_512L).listVersion(VERSION).lastError("main: HTTP 503").etag(ETAG)
                .build();
        when(blocklistSourceRepository.findAll(Sort.by("id"))).thenReturn(List.of(source));
        when(blocklistStore.memoryBytes(1)).thenReturn(1_234_567L);

        assertThat(blocklistSourceService.list()).containsExactly(new BlocklistSourceResponse(
                1L, "HaGeZi Fake", MAIN, List.of(FALLBACK), SourceKind.DOMAINS, BlocklistAction.BLOCK, true, 6,
                fetchedAt, fetchedAt, FALLBACK, 17_512L, VERSION, "main: HTTP 503", 1_234_567L));
    }

    private void assertRefused(final BlocklistSourceDto dto) {
        assertThatThrownBy(() -> blocklistSourceService.create(dto)).isInstanceOf(UrlValidationException.class);
        verify(blocklistSourceRepository, never()).save(any(BlocklistSource.class));
    }

    @Test
    void aSourceThatStartsFailingAlertsOnceAndNotOnTheNextFailure() {
        stored(source(1).build());
        fetcher.fails(MAIN, "HTTP 503");

        blocklistSourceService.refresh(1);
        blocklistSourceService.refresh(1);

        verify(alertPublisher, times(1)).publishEvent(new AdminAlertEvent.BlocklistSourceFailed("HaGeZi Fake", "HTTP 503", false));
        verifyNoMoreInteractions(alertPublisher);
    }

    @Test
    void aFailingSourceWhoseErrorChangesSendsNoNewAlert() {
        stored(source(1).lastError("HTTP 503").build());
        fetcher.fails(MAIN, "timed out");

        blocklistSourceService.refresh(1);

        verifyNoInteractions(alertPublisher);
    }

    @Test
    void aFailingSourceThatLoadsAgainAlertsItsRecovery() {
        stored(source(1).lastError("HTTP 503").build());
        fetcher.serves(MAIN, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        verify(alertPublisher).publishEvent(new AdminAlertEvent.BlocklistSourceRecovered("HaGeZi Fake"));
    }

    @Test
    void aHealthySourceThatLoadsAgainSendsNoAlert() {
        stored(source(1).build());
        fetcher.serves(MAIN, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        verifyNoInteractions(alertPublisher);
    }

    @Test
    void aFailingMainUrlAFallbackCoveredSaysTheFallbackLoaded() {
        stored(source(1).fallbackUrls(List.of(FALLBACK)).build());
        fetcher.fails(MAIN, "HTTP 503");
        fetcher.serves(FALLBACK, LIST);
        redisHasRoom();

        blocklistSourceService.refresh(1);

        verify(alertPublisher).publishEvent(new AdminAlertEvent.BlocklistSourceFailed("HaGeZi Fake", "main: HTTP 503", true));
    }

    private static String domains(final int count) {
        return IntStream.range(0, count).mapToObj(index -> "scam-" + index + ".example\n").collect(Collectors.joining());
    }

    private void redisHasRoom() {
        when(blocklistStore.memory()).thenReturn(new BlocklistStore.RedisMemory(10 * MEGABYTE, 0));
    }

    private BlocklistSource stored(final BlocklistSource source) {
        when(blocklistSourceRepository.findById(source.getId())).thenReturn(Optional.of(source));
        rows.put(source.getId(), source);
        return source;
    }

    private int recordedOnTheRow(final long id, final SourceKind kind, final Consumer<BlocklistSource> refreshColumns) {
        final var row = rows.get(id);
        if (row == null || !row.isEnabled() || row.getKind() != kind) {
            return 0;
        }
        refreshColumns.accept(row);
        return 1;
    }

    private void verifyNoRefreshRecorded() {
        verify(blocklistSourceRepository, never()).save(any());
        verify(blocklistSourceRepository, never()).recordRefresh(anyLong(), any(), any(), any());
        verify(blocklistSourceRepository, never()).recordLoadedList(anyLong(), any(), any(), any(), any(), anyLong(), any(), any(), any());
    }

    private static BlocklistSource.BlocklistSourceBuilder source(final long id) {
        return BlocklistSource.builder().id(id).name("HaGeZi Fake").url(MAIN).action(BlocklistAction.BLOCK);
    }

    private record Request(String url, String etag, String lastModified, long maxBytes, Duration requestTimeout) {}

    private static final class FakeFetcher extends PublicHttpFetcher {

        private final Path directory;
        private final Map<String, Function<Request, FetchResult>> answers = new HashMap<>();
        private final List<Request> requests = new ArrayList<>();
        private final List<Path> bodies = new ArrayList<>();

        private FakeFetcher(final Path directory) {
            super(url -> true);
            this.directory = directory;
        }

        @Override
        public FetchResult fetch(final String url, final long maxBytes, final String etag, final String lastModified,
                final Duration requestTimeout) {
            final var request = new Request(url, etag, lastModified, maxBytes, requestTimeout);
            requests.add(request);
            return answers.getOrDefault(url, unknown -> {
                throw new UrlValidationException("connection failed");
            }).apply(request);
        }

        private List<String> requestedUrls() {
            return requests.stream().map(Request::url).toList();
        }

        private void serves(final String url, final String body) {
            serves(url, body, null, null, () -> {});
        }

        private void serves(final String url, final String body, final Runnable during) {
            serves(url, body, null, null, during);
        }

        private void serves(final String url, final String body, final String etag, final String lastModified) {
            serves(url, body, etag, lastModified, () -> {});
        }

        private void serves(final String url, final String body, final String etag, final String lastModified, final Runnable during) {
            answers.put(url, request -> {
                during.run();
                return new FetchResult(false, write(body), etag, lastModified);
            });
        }

        private void servesAMissingFile(final String url) {
            answers.put(url, request -> new FetchResult(false, directory.resolve("deleted-before-reading.tmp"), null, null));
        }

        private void fails(final String url, final String message) {
            answers.put(url, request -> {
                throw new UrlValidationException(message);
            });
        }

        private void throwsOn(final String url, final RuntimeException exception) {
            answers.put(url, request -> {
                throw exception;
            });
        }

        private void notModified(final String url) {
            answers.put(url, request -> new FetchResult(true, null, request.etag(), request.lastModified()));
        }

        private Path write(final String body) {
            try {
                final var file = Files.createTempFile(directory, "hrva-fetch-", ".tmp");
                Files.writeString(file, body);
                bodies.add(file);
                return file;
            } catch (final IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

    }

}
