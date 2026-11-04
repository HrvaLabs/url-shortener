package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.stats.CacheStats;
import cc.hrva.urlshortener.dto.stats.RedirectTiming;
import cc.hrva.urlshortener.dto.stats.RequestCounts;
import cc.hrva.urlshortener.service.domain.UrlCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RuntimeMetrics {

    private static final String REDIRECT_TIMER = "redirect.duration";
    private static final String REQUEST_TIMER = "http.server.requests";
    private static final List<String> REDIRECT_SOURCES = List.of("cache", "db");

    private final MeterRegistry meterRegistry;
    private final UrlCache urlCache;
    private volatile Duration startup;

    @EventListener
    public void ready(final ApplicationReadyEvent event) {
        startup = event.getTimeTaken();
    }

    public Optional<Duration> startup() {
        return Optional.ofNullable(startup);
    }

    public OptionalDouble gcPauseMillis() {
        final var times = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(milliseconds -> milliseconds >= 0)
                .toArray();
        return times.length == 0 ? OptionalDouble.empty() : OptionalDouble.of(Arrays.stream(times).sum());
    }

    public List<RedirectTiming> redirectTimings() {
        return REDIRECT_SOURCES.stream().map(this::redirectTiming).toList();
    }

    public RequestCounts requestCounts() {
        final var total = countOf(meterRegistry.find(REQUEST_TIMER).timers());
        final var serverErrors = countOf(meterRegistry.find(REQUEST_TIMER).tag("outcome", "SERVER_ERROR").timers());
        return new RequestCounts(total, serverErrors);
    }

    public CacheStats urlCacheStats() {
        return urlCache.redirectStats();
    }

    public JvmHeap heap() {
        final var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new JvmHeap(heap.getUsed(), heap.getMax());
    }

    public Instant startedAt() {
        return Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    public Duration uptime() {
        return Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
    }

    public static String formatUptime(final Duration uptime) {
        final var days = uptime.toDays();
        final var hours = uptime.toHours() % 24;
        final var minutes = uptime.toMinutes() % 60;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        }
        return String.format("%dm", Math.max(1, minutes));
    }

    private RedirectTiming redirectTiming(final String source) {
        final var timer = meterRegistry.find(REDIRECT_TIMER).tag("source", source).timer();
        if (timer == null || timer.count() == 0) {
            return new RedirectTiming(source, 0, null, null);
        }
        return new RedirectTiming(source, timer.count(), timer.mean(TimeUnit.MILLISECONDS), timer.max(TimeUnit.MILLISECONDS));
    }

    private static long countOf(final Collection<Timer> timers) {
        return timers.stream().mapToLong(Timer::count).sum();
    }

    public record JvmHeap(long usedBytes, long maxBytes) {}

}
