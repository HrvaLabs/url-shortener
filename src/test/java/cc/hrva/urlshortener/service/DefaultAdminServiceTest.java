package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AdminStatsResponse;
import cc.hrva.urlshortener.dto.AdminStatsResponse.RecentUrl;
import cc.hrva.urlshortener.dto.stats.CacheStats;
import cc.hrva.urlshortener.dto.stats.RedirectTiming;
import cc.hrva.urlshortener.dto.stats.RequestCounts;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.impl.DefaultAdminService;
import cc.hrva.urlshortener.service.stats.AppVersion;
import cc.hrva.urlshortener.service.stats.HealthStatuses;
import cc.hrva.urlshortener.service.stats.RuntimeMetrics;
import cc.hrva.urlshortener.service.stats.RuntimeMetrics.JvmHeap;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAdminServiceTest {

    private static final String NO_VALUE = "—";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 25, 9, 30);

    @Mock
    private UserRepository userRepository;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private RuntimeMetrics runtimeMetrics;

    @Mock
    private HealthStatuses healthStatuses;

    @Mock
    private AppVersion appVersion;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        lenient().when(runtimeMetrics.redirectTimings()).thenReturn(List.of(
                new RedirectTiming("cache", 0, null, null),
                new RedirectTiming("db", 0, null, null)));
        lenient().when(runtimeMetrics.requestCounts()).thenReturn(new RequestCounts(0, 0));
        lenient().when(runtimeMetrics.urlCacheStats()).thenReturn(new CacheStats(0, 0, null));
        lenient().when(runtimeMetrics.heap()).thenReturn(new JvmHeap(256L * 1024 * 1024, 1024L * 1024 * 1024));
        lenient().when(runtimeMetrics.uptime()).thenReturn(Duration.ofHours(3).plusMinutes(12));
        lenient().when(healthStatuses.status("db")).thenReturn("UP");
        lenient().when(healthStatuses.status("redis")).thenReturn("UP");
        lenient().when(appVersion.current()).thenReturn("2.8.1");

        adminService = new DefaultAdminService(userRepository, urlRepository, apiKeyRepository, new MockEnvironment(),
                runtimeMetrics, healthStatuses, appVersion);
    }

    @Test
    void appVersionComesFromTheChangelog() {
        when(appVersion.current()).thenReturn("2.9");

        assertThat(stats().appVersion()).isEqualTo("2.9");
    }

    @Test
    void healthFlagsAreReal() {
        when(healthStatuses.status("db")).thenReturn("UP");
        when(healthStatuses.status("redis")).thenReturn("DOWN");

        final var down = stats();

        assertThat(down.databaseActive()).isTrue();
        assertThat(down.cacheActive()).isFalse();

        when(healthStatuses.status("redis")).thenReturn("UNKNOWN");

        assertThat(stats().cacheActive()).isFalse();
    }

    @Test
    void redirectTimingCombinesBothSources() {
        when(runtimeMetrics.redirectTimings()).thenReturn(List.of(
                new RedirectTiming("cache", 2, 20.0, 30.0),
                new RedirectTiming("db", 1, 50.0, 50.0)));

        final var stats = stats();

        assertThat(stats.redirectCount()).isEqualTo(3);
        assertThat(stats.redirectAvgMs()).isEqualTo("30");
        assertThat(stats.redirectMaxMs()).isEqualTo("50");
    }

    @Test
    void noRedirectsYetShowDashes() {
        final var stats = stats();

        assertThat(stats.redirectAvgMs()).isEqualTo(NO_VALUE);
        assertThat(stats.redirectMaxMs()).isEqualTo(NO_VALUE);
        assertThat(stats.redirectCount()).isZero();
    }

    @Test
    void requestsCountIsTheTimerCount() {
        when(runtimeMetrics.requestCounts()).thenReturn(new RequestCounts(3, 1));

        assertThat(stats().requestsCount()).isEqualTo(3);
    }

    @Test
    void cacheHitRatioIsRealOrADash() {
        when(runtimeMetrics.urlCacheStats()).thenReturn(new CacheStats(9, 1, 90.0));

        assertThat(stats().cacheHitRatio()).isEqualTo("90.0%");

        when(runtimeMetrics.urlCacheStats()).thenReturn(new CacheStats(0, 0, null));

        assertThat(stats().cacheHitRatio()).isEqualTo(NO_VALUE).isNotEqualTo("Active");
    }

    @Test
    void recentUrlsAreTheNewestTen() {
        final var owner = User.builder().email("owner@hrva.cc").build();
        when(urlRepository.findTop10ByOrderByCreateDateDescIdDesc()).thenReturn(List.of(
                url(12L, "newest", owner, CREATED.plusMinutes(5), 4L),
                url(11L, "anon", null, CREATED, 0L)));

        assertThat(stats().recentUrls()).containsExactly(
                new RecentUrl(12L, "newest", "https://newest.example", CREATED.plusMinutes(5), 4L, "owner@hrva.cc"),
                new RecentUrl(11L, "anon", "https://anon.example", CREATED, 0L, null));
    }

    @Test
    void uptimeAndHeapAreFormattedAsBefore() {
        final var stats = stats();

        assertThat(stats.uptime()).isEqualTo("3h 12m");
        assertThat(stats.jvmMemoryUsed()).isEqualTo("256 MB");
        assertThat(stats.jvmMemoryMax()).isEqualTo("1024 MB");
    }

    private AdminStatsResponse stats() {
        return adminService.getDashboardStats();
    }

    private static Url url(final Long id, final String shortUrl, final User owner, final LocalDateTime createDate, final Long visits) {
        return Url.builder()
                .id(id)
                .shortUrl(shortUrl)
                .longUrl("https://" + shortUrl + ".example")
                .owner(owner)
                .createDate(createDate)
                .visits(visits)
                .build();
    }

}
