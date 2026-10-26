package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.DailyCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.ApiKeyCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.McpCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.UserInventory;
import cc.hrva.urlshortener.repository.stats.HourlyCount;
import cc.hrva.urlshortener.repository.stats.HourlyCountRepository;
import cc.hrva.urlshortener.repository.stats.UsersStatsRepository;
import cc.hrva.urlshortener.repository.stats.UsersStatsRepository.AccountCounts;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cc.hrva.urlshortener.repository.stats.HourlySeries.SIGNUPS_BY_PROVIDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsersStatsTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final LocalDateTime STORED_NOW = LocalDateTime.of(2026, 10, 26, 10, 0);
    private static final LocalDate FROM = LocalDate.of(2026, 10, 20);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);
    private static final StatsWindow WINDOW =
            new StatsWindow(7, FROM, TO, LocalDateTime.of(2026, 10, 19, 22, 0), LocalDateTime.of(2026, 10, 26, 23, 0));
    private static final List<String> PROVIDERS = List.of("local", "google", "github");
    private static final AccountCounts NO_ACCOUNTS = new AccountCounts(0, 0, 0, 0);
    private static final ApiKeyCounts NO_API_KEYS = new ApiKeyCounts(0, 0, 0, 0);
    private static final McpCounts NO_MCP = new McpCounts(0, 0);

    @Mock
    private UsersStatsRepository usersStatsRepository;

    @Mock
    private HourlyCountRepository hourlyCountRepository;

    private UsersStats usersStats;

    @BeforeEach
    void setUp() {
        this.usersStats = usersStatsAt(NOW);
    }

    @Test
    void deactivatedIsTotalMinusActive() {
        givenCounts(new AccountCounts(120, 110, 100, 8), STORED_NOW, NO_API_KEYS, NO_MCP);
        when(usersStatsRepository.countAdmins()).thenReturn(1L);
        when(usersStatsRepository.countByProvider()).thenReturn(List.of(
                new CountEntry("local", 90),
                new CountEntry("google", 25),
                new CountEntry("github", 5)));

        assertThat(usersStats.collect(WINDOW).now()).isEqualTo(new UserInventory(120, 110, 10, 1, 100, 8, List.of(
                new CountEntry("local", 90),
                new CountEntry("google", 25),
                new CountEntry("github", 5))));
    }

    @Test
    void signupsUseTheProviderKeys() {
        when(hourlyCountRepository.count(SIGNUPS_BY_PROVIDER, WINDOW.start(), WINDOW.end())).thenReturn(List.of(
                new HourlyCount(LocalDate.of(2026, 10, 24), 22, "github", 1),
                new HourlyCount(LocalDate.of(2026, 10, 25), 9, "local", 2),
                new HourlyCount(LocalDate.of(2026, 10, 25), 10, "gitlab", 1)));
        givenCounts(NO_ACCOUNTS, STORED_NOW, NO_API_KEYS, NO_MCP);

        final var signups = usersStats.collect(WINDOW).signups();

        assertThat(signups.keys()).containsExactly("local", "google", "github", "gitlab");
        assertThat(signups.daily()).hasSize(7);
        assertThat(signups.daily()).filteredOn(day -> day.date().equals(LocalDate.of(2026, 10, 25))).singleElement()
                .extracting(DailyCounts::counts)
                .satisfies(counts -> assertThat(counts).containsExactly(
                        entry("local", 2L), entry("google", 0L), entry("github", 1L), entry("gitlab", 1L)));
    }

    @Test
    void activityWindowsAreSevenAndThirtyDaysBackFromNow() {
        givenCounts(NO_ACCOUNTS, STORED_NOW, NO_API_KEYS, NO_MCP);
        when(usersStatsRepository.countSignedInSince(LocalDateTime.of(2026, 10, 19, 10, 0))).thenReturn(30L);
        when(usersStatsRepository.countSignedInSince(LocalDateTime.of(2026, 9, 26, 10, 0))).thenReturn(60L);

        final var users = usersStats.collect(WINDOW);

        final var since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(usersStatsRepository, times(2)).countSignedInSince(since.capture());
        assertThat(since.getAllValues()).containsExactly(LocalDateTime.of(2026, 10, 19, 10, 0), LocalDateTime.of(2026, 9, 26, 10, 0));
        assertThat(users.activeLast7Days()).isEqualTo(30);
        assertThat(users.activeLast30Days()).isEqualTo(60);
    }

    @Test
    void apiKeysAndMcpUseTheStoredDateAndTime() {
        final var storedNow = LocalDateTime.of(2026, 10, 25, 23, 30);
        final var usersStatsJustAfterZagrebMidnight = usersStatsAt(Instant.parse("2026-10-25T23:30:00Z"));
        givenCounts(NO_ACCOUNTS, storedNow, new ApiKeyCounts(20, 5, 3, 15000), new McpCounts(4, 9));

        final var users = usersStatsJustAfterZagrebMidnight.collect(WINDOW);

        assertThat(users.apiKeys()).isEqualTo(new ApiKeyCounts(20, 5, 3, 15000));
        assertThat(users.mcp()).isEqualTo(new McpCounts(4, 9));
        verify(usersStatsRepository).countApiKeys(LocalDate.of(2026, 10, 25));
        verify(usersStatsRepository).countMcp(storedNow);
    }

    @Test
    void emptySiteIsAllZeros() {
        givenCounts(NO_ACCOUNTS, STORED_NOW, NO_API_KEYS, NO_MCP);

        final var users = usersStats.collect(WINDOW);

        assertThat(users.days()).isEqualTo(7);
        assertThat(users.from()).isEqualTo(FROM);
        assertThat(users.to()).isEqualTo(TO);
        assertThat(users.signups().keys()).containsExactlyElementsOf(PROVIDERS);
        assertThat(users.signups().daily()).extracting(DailyCounts::date)
                .containsExactlyElementsOf(FROM.datesUntil(TO.plusDays(1)).toList());
        assertThat(users.signups().daily()).allSatisfy(day -> {
            assertThat(day.counts().keySet()).containsExactlyElementsOf(PROVIDERS);
            assertThat(day.counts().values()).containsOnly(0L);
        });
        assertThat(users.now()).isEqualTo(new UserInventory(0, 0, 0, 0, 0, 0, List.of()));
        assertThat(users.activeLast7Days()).isZero();
        assertThat(users.activeLast30Days()).isZero();
        assertThat(users.apiKeys()).isEqualTo(new ApiKeyCounts(0, 0, 0, 0));
        assertThat(users.mcp()).isEqualTo(new McpCounts(0, 0));
        verify(hourlyCountRepository).count(SIGNUPS_BY_PROVIDER, WINDOW.start(), WINDOW.end());
        verify(usersStatsRepository).countAccounts();
        verify(usersStatsRepository).countAdmins();
        verify(usersStatsRepository).countByProvider();
        verify(usersStatsRepository).countSignedInSince(STORED_NOW.minusDays(7));
        verify(usersStatsRepository).countSignedInSince(STORED_NOW.minusDays(30));
        verify(usersStatsRepository).countApiKeys(STORED_NOW.toLocalDate());
        verify(usersStatsRepository).countMcp(STORED_NOW);
    }

    private UsersStats usersStatsAt(final Instant now) {
        final var clock = Clock.fixed(now, ZoneOffset.UTC);
        final var appProperties = new AppProperties();
        appProperties.getAnalytics().setZone(ZoneId.of("Europe/Zagreb"));
        final var statsDays = new StatsDays(new AnalyticsDays(clock, appProperties), clock, ZoneOffset.UTC);
        return new UsersStats(usersStatsRepository, hourlyCountRepository, new DailyBuckets(statsDays), statsDays);
    }

    private void givenCounts(final AccountCounts accounts, final LocalDateTime storedNow, final ApiKeyCounts apiKeys, final McpCounts mcp) {
        when(usersStatsRepository.countAccounts()).thenReturn(accounts);
        when(usersStatsRepository.countApiKeys(storedNow.toLocalDate())).thenReturn(apiKeys);
        when(usersStatsRepository.countMcp(storedNow)).thenReturn(mcp);
    }

}
