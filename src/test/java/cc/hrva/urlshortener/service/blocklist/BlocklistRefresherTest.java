package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.client.FetchResult;
import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlocklistRefresherTest {

    private static final long TRANCO = 10L;
    private static final String TRANCO_NAME = "Tranco top sites";
    private static final String API = "https://tranco-list.eu/api/lists/date/latest?subdomains=true";
    private static final String LATEST = """
            {"list_id": "K9YXW", "available": true, "failed": false, "download": "https://tranco-list.eu/download/K9YXW/1000000",
             "created_on": "2026-11-03T22:00:02.695114"}
            """;
    private static final String TOP_20000 = "https://tranco-list.eu/download/K9YXW/20000";
    private static final String TOP_1000 = "https://tranco-list.eu/download/K9YXW/1000";
    private static final String ETAG = "\"K9YXW-20000\"";
    private static final String NO_DOWNLOAD_LINK = "No download link found. Use the Tranco API address of the latest list.";
    private static final long MEGABYTE = 1024L * 1024L;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @TempDir
    private Path downloads;

    @Mock
    private BlocklistSourceRepository blocklistSourceRepository;

    @Mock
    private BlocklistStore blocklistStore;

    @Mock
    private PublicHttpFetcher publicHttpFetcher;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BlocklistHealth blocklistHealth;

    private final List<Path> bodies = new ArrayList<>();
    private final DefaultSettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());
    private BlocklistRefresher blocklistRefresher;

    @BeforeEach
    void setUp() {
        final var memoryGuard = new BlocklistMemoryGuard(blocklistStore, blocklistSourceRepository, new AppProperties());
        blocklistRefresher = new BlocklistRefresher(blocklistSourceRepository, blocklistStore, memoryGuard, publicHttpFetcher,
                new BlocklistParser(), new LinkListParser(), new TopSitesParser(), settingsService, eventPublisher, blocklistHealth);
    }

    @Test
    void aTopSitesSourceDownloadsTheLatestListAtTheConfiguredSize() {
        final var source = stored(tranco().build());
        serves(API, LATEST);
        serves(TOP_20000, "74,accounts.google.com\r\n94,login.microsoftonline.com\r\n359,m.facebook.com\r\n");
        loadsInto(source);
        redisHasRoom();

        blocklistRefresher.refresh(source, RefreshTrigger.MANUAL);

        verify(publicHttpFetcher).fetch(eq(API), anyLong(), isNull(), isNull(), any(Duration.class));
        verify(publicHttpFetcher).fetch(eq(TOP_20000), eq(100L * MEGABYTE), isNull(), isNull(), eq(Duration.ofSeconds(60)));
        verify(blocklistStore).replace(TRANCO, Set.of("accounts.google.com", "login.microsoftonline.com", "m.facebook.com"));
        verify(blocklistSourceRepository).recordLoadedList(eq(TRANCO), eq(SourceKind.TOP_SITES), any(), isNull(), eq(TOP_20000), eq(3L), isNull(),
                isNull(), isNull());
        verify(blocklistHealth).refreshed(TRANCO);
        assertThat(bodies).noneMatch(Files::exists);
    }

    @Test
    void aTopSitesListKeepsOnlyTheConfiguredNumberOfSites() {
        settingsService.change(SettingKey.SIGN_IN_FORM_TOP_SITES, NODES.numberNode(1000), "admin@hrva.cc");
        final var source = stored(tranco().build());
        serves(API, LATEST);
        serves(TOP_1000, rankedDomains(1200));
        loadsInto(source);
        redisHasRoom();

        blocklistRefresher.refresh(source, RefreshTrigger.MANUAL);

        verify(blocklistStore).replace(TRANCO, Set.copyOf(rankedDomainNames(1000)));
    }

    @Test
    void aScheduledRefreshAfterTheSizeWasLoweredLoadsTheSmallerList() {
        settingsService.change(SettingKey.SIGN_IN_FORM_TOP_SITES, NODES.numberNode(1000), "admin@hrva.cc");
        final var source = stored(tranco().domainCount(20_000L).lastSuccessUrl(TOP_20000).build());
        serves(API, LATEST);
        serves(TOP_1000, rankedDomains(1000));
        lenient().when(blocklistStore.exists(TRANCO)).thenReturn(true);
        loadsInto(source);
        redisHasRoom();

        blocklistRefresher.refresh(source, RefreshTrigger.SCHEDULED);

        verify(blocklistStore).replace(TRANCO, Set.copyOf(rankedDomainNames(1000)));
        verify(blocklistSourceRepository).recordLoadedList(eq(TRANCO), eq(SourceKind.TOP_SITES), any(), isNull(), eq(TOP_1000), eq(1000L), isNull(),
                isNull(), isNull());
    }

    @Test
    void aScheduledTopSitesDownloadOfLessThanHalfTheSitesKeepsTheOldList() {
        final var source = stored(tranco().domainCount(20_000L).build());
        serves(API, LATEST);
        serves(TOP_20000, rankedDomains(1200));
        when(blocklistStore.exists(TRANCO)).thenReturn(true);
        when(blocklistSourceRepository.recordRefresh(anyLong(), any(), any(), any())).thenReturn(1);

        blocklistRefresher.refresh(source, RefreshTrigger.SCHEDULED);

        verify(blocklistStore, never()).replace(anyLong(), anySet());
        verify(blocklistSourceRepository).recordRefresh(eq(TRANCO), eq(SourceKind.TOP_SITES), any(),
                eq("The new download has 1,200 entries, less than half of the 20,000 loaded before. Kept the old list."));
    }

    @Test
    void theSameListAtTheSameSizeIsRevalidatedWhileTheApiAnswerIsAlwaysFetched() {
        final var source = stored(tranco().domainCount(20_000L).lastSuccessUrl(TOP_20000).etag(ETAG).build());
        serves(API, LATEST);
        when(blocklistStore.exists(TRANCO)).thenReturn(true);
        when(publicHttpFetcher.fetch(eq(TOP_20000), anyLong(), eq(ETAG), isNull(), any(Duration.class)))
                .thenReturn(new FetchResult(true, null, ETAG, null, TOP_20000));
        when(blocklistSourceRepository.recordUnchangedList(anyLong(), any(), any(), any())).thenReturn(1);

        blocklistRefresher.refresh(source, RefreshTrigger.SCHEDULED);

        verify(publicHttpFetcher).fetch(eq(API), anyLong(), isNull(), isNull(), any(Duration.class));
        verify(blocklistSourceRepository).recordUnchangedList(eq(TRANCO), eq(SourceKind.TOP_SITES), any(), isNull());
        verify(blocklistStore, never()).replace(anyLong(), anySet());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"list_id\": \"K9YXW\", \"available\": true}",
            "{\"download\": \"\"}",
            "{\"download\": 1000000}",
            "[\"https://tranco-list.eu/download/K9YXW/1000000\"]",
            "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>",
            ""})
    void anApiAnswerWithoutADownloadLinkKeepsTheLastList(final String answer) {
        assertTheRefreshFailsWith(answer, NO_DOWNLOAD_LINK);
    }

    @Test
    void aDownloadLinkWithoutAListSizeKeepsTheLastList() {
        assertTheRefreshFailsWith("{\"download\": \"https://tranco-list.eu/download/K9YXW/full\"}", "The download link doesn't end in a list size.");
    }

    private void assertTheRefreshFailsWith(final String answer, final String error) {
        final var source = stored(tranco().domainCount(20_000L).lastSuccessUrl(TOP_20000).build());
        serves(API, answer);
        when(blocklistSourceRepository.recordRefresh(anyLong(), any(), any(), any())).thenReturn(1);

        blocklistRefresher.refresh(source, RefreshTrigger.SCHEDULED);

        verify(publicHttpFetcher).fetch(eq(API), anyLong(), isNull(), isNull(), any(Duration.class));
        verify(blocklistStore, never()).replace(anyLong(), anySet());
        verify(blocklistSourceRepository).recordRefresh(eq(TRANCO), eq(SourceKind.TOP_SITES), any(), eq(error));
        verify(blocklistSourceRepository, never()).recordLoadedList(anyLong(), any(), any(), any(), any(), anyLong(), any(), any(), any());
        verify(blocklistHealth).failed(TRANCO);
        verify(eventPublisher).publishEvent(new AdminAlertEvent.BlocklistSourceFailed(TRANCO_NAME, error, false));
        assertThat(bodies).noneMatch(Files::exists);
    }

    private static BlocklistSource.BlocklistSourceBuilder tranco() {
        return BlocklistSource.builder().id(TRANCO).name(TRANCO_NAME).url(API).kind(SourceKind.TOP_SITES).action(BlocklistAction.TRUST)
                .refreshIntervalHours(24);
    }

    private BlocklistSource stored(final BlocklistSource source) {
        when(blocklistSourceRepository.findById(source.getId())).thenReturn(Optional.of(source));
        return source;
    }

    private void loadsInto(final BlocklistSource source) {
        when(blocklistSourceRepository.recordLoadedList(eq(source.getId()), eq(source.getKind()), any(), any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(1);
    }

    private void redisHasRoom() {
        when(blocklistStore.memory()).thenReturn(new BlocklistStore.RedisMemory(10 * MEGABYTE, 0));
    }

    private void serves(final String url, final String body) {
        when(publicHttpFetcher.fetch(eq(url), anyLong(), isNull(), isNull(), any(Duration.class)))
                .thenAnswer(invocation -> new FetchResult(false, write(body), null, null, url));
    }

    private Path write(final String body) {
        try {
            final var file = Files.writeString(Files.createTempFile(downloads, "download", ".tmp"), body);
            bodies.add(file);
            return file;
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String rankedDomains(final int count) {
        final var names = rankedDomainNames(count);
        return IntStream.range(0, count).mapToObj(index -> (index + 1) + "," + names.get(index)).collect(Collectors.joining("\r\n", "", "\r\n"));
    }

    private static List<String> rankedDomainNames(final int count) {
        return IntStream.rangeClosed(1, count).mapToObj(rank -> "site-" + rank + ".example").toList();
    }

}
