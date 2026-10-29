package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.DailyCounts;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.CreatedInRange;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.DeadDestinationCounts;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.LinkInventory;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.NewestLink;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CreatedVia;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.stats.HourlyCount;
import cc.hrva.urlshortener.repository.stats.HourlyCountRepository;
import cc.hrva.urlshortener.repository.stats.LinksStatsRepository;
import cc.hrva.urlshortener.repository.stats.LinksStatsRepository.CreatedCounts;
import cc.hrva.urlshortener.repository.stats.LinksStatsRepository.InventoryCounts;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cc.hrva.urlshortener.repository.stats.HourlySeries.LINKS_BY_CHANNEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinksStatsTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final LocalDateTime STORED_NOW = LocalDateTime.of(2026, 10, 26, 10, 0);
    private static final LocalDate FROM = LocalDate.of(2026, 10, 20);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);
    private static final StatsWindow WINDOW =
            new StatsWindow(7, FROM, TO, LocalDateTime.of(2026, 10, 19, 22, 0), LocalDateTime.of(2026, 10, 26, 23, 0));
    private static final List<String> CHANNELS = List.of("WEB", "API_KEY", "BULK", "MCP", "ANONYMOUS_WEB", "ANONYMOUS_MCP", "unknown");
    private static final CreatedCounts NO_LINKS_CREATED = new CreatedCounts(0, 0, 0);
    private static final InventoryCounts NO_LINKS = new InventoryCounts(0, 0, 0, 0, 0, 0);

    @Mock
    private LinksStatsRepository linksStatsRepository;

    @Mock
    private HourlyCountRepository hourlyCountRepository;

    @Mock
    private UrlRepository urlRepository;

    private LinksStats linksStats;

    @BeforeEach
    void setUp() {
        final var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final var appProperties = new AppProperties();
        appProperties.getAnalytics().setZone(ZoneId.of("Europe/Zagreb"));
        final var statsDays = new StatsDays(new AnalyticsDays(clock, appProperties), clock, ZoneOffset.UTC);
        this.linksStats = new LinksStats(linksStatsRepository, hourlyCountRepository, new DailyBuckets(statsDays), urlRepository, statsDays);
    }

    @Test
    void createdSeriesUsesTheChannelKeys() {
        when(hourlyCountRepository.count(LINKS_BY_CHANNEL, WINDOW.start(), WINDOW.end())).thenReturn(List.of(
                new HourlyCount(LocalDate.of(2026, 10, 24), 22, "WEB", 2),
                new HourlyCount(LocalDate.of(2026, 10, 25), 9, "unknown", 1),
                new HourlyCount(LocalDate.of(2026, 10, 25), 10, "ANONYMOUS_MCP", 3)));
        givenCounts(NO_LINKS_CREATED, NO_LINKS);

        final var created = linksStats.collect(WINDOW).created();

        assertThat(created.keys()).containsExactlyElementsOf(CHANNELS);
        assertThat(created.daily()).hasSize(7);
        assertThat(created.daily()).filteredOn(day -> day.date().equals(LocalDate.of(2026, 10, 25))).singleElement()
                .extracting(DailyCounts::counts)
                .satisfies(counts -> assertThat(counts).containsExactly(
                        entry("WEB", 2L), entry("API_KEY", 0L), entry("BULK", 0L), entry("MCP", 0L),
                        entry("ANONYMOUS_WEB", 0L), entry("ANONYMOUS_MCP", 3L), entry("unknown", 1L)));
    }

    @Test
    void createdInRangeSplitsAccountAndAnonymous() {
        givenCounts(new CreatedCounts(40, 6, 5), NO_LINKS);

        assertThat(linksStats.collect(WINDOW).createdInRange()).isEqualTo(new CreatedInRange(40, 34, 6, 5));
    }

    @Test
    void inactiveIsTotalMinusActive() {
        givenCounts(NO_LINKS_CREATED, new InventoryCounts(900, 700, 50, 12, 30, 80));
        when(linksStatsRepository.countBlockedByReason()).thenReturn(List.of(
                new CountEntry("BLOCKLIST", 10),
                new CountEntry("SAFE_BROWSING", 2)));

        assertThat(linksStats.collect(WINDOW).now()).isEqualTo(new LinkInventory(900, 700, 200, 50, 12, 30, 80, List.of(
                new CountEntry("BLOCKLIST", 10),
                new CountEntry("SAFE_BROWSING", 2))));
    }

    @Test
    void topTagsAndDomainsAreTheTopTen() {
        givenCounts(NO_LINKS_CREATED, NO_LINKS);
        when(linksStatsRepository.findTopTags(10)).thenReturn(List.of(new CountEntry("ai", 120)));
        when(linksStatsRepository.findTopDomains(10)).thenReturn(List.of(new CountEntry("github.com", 30)));

        final var links = linksStats.collect(WINDOW);

        assertThat(links.topTags()).containsExactly(new CountEntry("ai", 120));
        assertThat(links.topDomains()).containsExactly(new CountEntry("github.com", 30));
    }

    @Test
    void newestLinksCarryChannelHostAndStatus() {
        final var anonymous = Url.builder()
                .id(905L)
                .shortUrl("abc12")
                .longUrl("https://Example.com/menu?table=4")
                .createdVia(CreatedVia.ANONYMOUS_WEB)
                .createDate(LocalDateTime.of(2026, 10, 25, 9, 0))
                .visits(0L)
                .active(true)
                .build();
        final var blocked = Url.builder()
                .id(904L)
                .shortUrl("flyer")
                .longUrl("https://shop.example.org/flyer")
                .owner(User.builder().email("ana@example.com").build())
                .createDate(LocalDateTime.of(2026, 10, 25, 8, 30))
                .visits(0L)
                .blockReason(BlockReason.BLOCKLIST)
                .active(false)
                .build();
        givenCounts(NO_LINKS_CREATED, NO_LINKS);
        when(urlRepository.findTop10ByOrderByCreateDateDescIdDesc()).thenReturn(List.of(anonymous, blocked));

        assertThat(linksStats.collect(WINDOW).newest()).containsExactly(
                new NewestLink(905L, "abc12", "example.com", null, "ANONYMOUS_WEB", LocalDateTime.of(2026, 10, 25, 9, 0), "ACTIVE"),
                new NewestLink(904L, "flyer", "shop.example.org", "ana@example.com", "unknown", LocalDateTime.of(2026, 10, 25, 8, 30),
                        "BLOCKED"));
    }

    @Test
    void emptySiteIsAllZeros() {
        givenCounts(NO_LINKS_CREATED, NO_LINKS);
        when(linksStatsRepository.countDeadDestinations()).thenReturn(new DeadDestinationCounts(0, 0));

        final var links = linksStats.collect(WINDOW);

        assertThat(links.days()).isEqualTo(7);
        assertThat(links.from()).isEqualTo(FROM);
        assertThat(links.to()).isEqualTo(TO);
        assertThat(links.created().keys()).containsExactlyElementsOf(CHANNELS);
        assertThat(links.created().daily()).extracting(DailyCounts::date)
                .containsExactlyElementsOf(FROM.datesUntil(TO.plusDays(1)).toList());
        assertThat(links.created().daily()).allSatisfy(day -> {
            assertThat(day.counts().keySet()).containsExactlyElementsOf(CHANNELS);
            assertThat(day.counts().values()).containsOnly(0L);
        });
        assertThat(links.createdInRange()).isEqualTo(new CreatedInRange(0, 0, 0, 0));
        assertThat(links.now()).isEqualTo(new LinkInventory(0, 0, 0, 0, 0, 0, 0, List.of()));
        assertThat(links.topTags()).isEmpty();
        assertThat(links.topDomains()).isEmpty();
        assertThat(links.newest()).isEmpty();
        assertThat(links.deadDestinations()).isEqualTo(new DeadDestinationCounts(0, 0));
        verify(hourlyCountRepository).count(LINKS_BY_CHANNEL, WINDOW.start(), WINDOW.end());
        verify(linksStatsRepository).countCreated(WINDOW.start(), WINDOW.end());
        verify(linksStatsRepository).countInventory(STORED_NOW);
        verify(linksStatsRepository).countBlockedByReason();
        verify(linksStatsRepository).findTopTags(10);
        verify(linksStatsRepository).findTopDomains(10);
        verify(urlRepository).findTop10ByOrderByCreateDateDescIdDesc();
    }

    @Test
    void deadDestinationsAreCountedWithTheAnonymousSplit() {
        givenCounts(NO_LINKS_CREATED, NO_LINKS);
        when(linksStatsRepository.countDeadDestinations()).thenReturn(new DeadDestinationCounts(12, 3));

        assertThat(linksStats.collect(WINDOW).deadDestinations()).isEqualTo(new DeadDestinationCounts(12, 3));
    }

    private void givenCounts(final CreatedCounts created, final InventoryCounts inventory) {
        when(linksStatsRepository.countCreated(WINDOW.start(), WINDOW.end())).thenReturn(created);
        when(linksStatsRepository.countInventory(STORED_NOW)).thenReturn(inventory);
    }

}
