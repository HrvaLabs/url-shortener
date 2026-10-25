package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.stats.CacheStats;
import cc.hrva.urlshortener.dto.stats.RedirectTiming;
import cc.hrva.urlshortener.dto.stats.RequestCounts;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.RedisCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void redirectTimingsPerSource() {
        redirect("cache", 10);
        redirect("cache", 30);
        redirect("db", 50);

        assertThat(runtimeMetrics().redirectTimings()).containsExactly(
                new RedirectTiming("cache", 2, 20.0, 30.0),
                new RedirectTiming("db", 1, 50.0, 50.0));
    }

    @Test
    void aSourceWithoutRedirectsHasNoTimes() {
        redirect("cache", 10);

        assertThat(runtimeMetrics().redirectTimings()).containsExactly(
                new RedirectTiming("cache", 1, 10.0, 10.0),
                new RedirectTiming("db", 0, null, null));
    }

    @Test
    void requestCountsSumEveryTimerAndTheServerErrors() {
        assertThat(runtimeMetrics().requestCounts()).isEqualTo(new RequestCounts(0, 0));

        request("/a", "SUCCESS");
        request("/a", "SUCCESS");
        request("/b", "SERVER_ERROR");

        assertThat(runtimeMetrics().requestCounts()).isEqualTo(new RequestCounts(3, 1));
    }

    @Test
    void urlCacheStatsFromTheRedisCache() {
        assertThat(runtimeMetrics(redisCacheManager(9, 1)).urlCacheStats()).isEqualTo(new CacheStats(9, 1, 90.0));
    }

    @Test
    void urlCacheStatsWithoutLookupsHaveNoRatio() {
        assertThat(runtimeMetrics(redisCacheManager(0, 0)).urlCacheStats()).isEqualTo(new CacheStats(0, 0, null));
    }

    @Test
    void aNonRedisCacheHasNoStats() {
        assertThat(runtimeMetrics(new ConcurrentMapCacheManager("urls")).urlCacheStats()).isEqualTo(new CacheStats(0, 0, null));
    }

    @Test
    void aFailingCacheHasNoStats() {
        final var cacheManager = mock(CacheManager.class);
        when(cacheManager.getCache("urls")).thenThrow(new IllegalStateException("Redis is down"));

        assertThat(runtimeMetrics(cacheManager).urlCacheStats()).isEqualTo(new CacheStats(0, 0, null));
    }

    @Test
    void uptimeIsFormattedAsBefore() {
        assertThat(RuntimeMetrics.formatUptime(Duration.ofSeconds(30))).isEqualTo("1m");
        assertThat(RuntimeMetrics.formatUptime(Duration.ofHours(3).plusMinutes(12))).isEqualTo("3h 12m");
        assertThat(RuntimeMetrics.formatUptime(Duration.ofDays(2).plusHours(3).plusMinutes(4))).isEqualTo("2d 3h 4m");
    }

    private void redirect(final String source, final long millis) {
        Timer.builder("redirect.duration").tag("source", source).register(meterRegistry).record(Duration.ofMillis(millis));
    }

    private void request(final String uri, final String outcome) {
        Timer.builder("http.server.requests").tags("uri", uri, "outcome", outcome).register(meterRegistry).record(Duration.ofMillis(5));
    }

    private RuntimeMetrics runtimeMetrics() {
        return runtimeMetrics(new ConcurrentMapCacheManager("urls"));
    }

    private RuntimeMetrics runtimeMetrics(final CacheManager cacheManager) {
        return new RuntimeMetrics(meterRegistry, cacheManager);
    }

    private static CacheManager redisCacheManager(final long hits, final long misses) {
        final var statistics = mock(CacheStatistics.class);
        when(statistics.getHits()).thenReturn(hits);
        when(statistics.getMisses()).thenReturn(misses);
        final var cache = mock(RedisCache.class);
        when(cache.getStatistics()).thenReturn(statistics);
        final var cacheManager = mock(CacheManager.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        return cacheManager;
    }

}
