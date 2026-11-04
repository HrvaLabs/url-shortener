package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.stats.CacheStats;
import cc.hrva.urlshortener.dto.stats.RedirectTiming;
import cc.hrva.urlshortener.dto.stats.RequestCounts;
import cc.hrva.urlshortener.service.domain.UrlCache;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
    void healthReportsTheRedirectHitRatio() {
        final var urlCache = new UrlCache(new ConcurrentMapCacheManager(UrlCache.CACHE_NAME), mock(TaskScheduler.class), Clock.systemUTC());
        urlCache.recordRedirectHit();
        urlCache.recordRedirectHit();
        urlCache.recordRedirectHit();
        urlCache.recordRedirectMiss();

        assertThat(runtimeMetrics(urlCache).urlCacheStats()).isEqualTo(urlCache.redirectStats()).isEqualTo(new CacheStats(3, 1, 75.0));
    }

    @Test
    void urlCacheStatsWithoutRedirectsHaveNoRatio() {
        assertThat(runtimeMetrics().urlCacheStats()).isEqualTo(new CacheStats(0, 0, null));
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

    @Test
    void theStartupTimeIsKnownOnceTheAppIsReady() {
        final var runtimeMetrics = runtimeMetrics();

        assertThat(runtimeMetrics.startup()).isEmpty();

        runtimeMetrics.ready(new ApplicationReadyEvent(new SpringApplication(), new String[0], mock(ConfigurableApplicationContext.class),
                Duration.ofMillis(25_512)));

        assertThat(runtimeMetrics.startup()).contains(Duration.ofMillis(25_512));
    }

    @Test
    void garbageCollectionTimeIsTheSumOverTheCollectors() {
        final var expected = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(milliseconds -> milliseconds >= 0)
                .sum();

        assertThat(runtimeMetrics().gcPauseMillis().orElseThrow()).isGreaterThanOrEqualTo(expected);
    }

    private RuntimeMetrics runtimeMetrics() {
        return runtimeMetrics(new UrlCache(new ConcurrentMapCacheManager(UrlCache.CACHE_NAME), mock(TaskScheduler.class), Clock.systemUTC()));
    }

    private RuntimeMetrics runtimeMetrics(final UrlCache urlCache) {
        return new RuntimeMetrics(meterRegistry, urlCache);
    }

}
