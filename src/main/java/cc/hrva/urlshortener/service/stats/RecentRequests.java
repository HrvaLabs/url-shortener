package cc.hrva.urlshortener.service.stats;

import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

@Component
public class RecentRequests {

    public static final int WINDOW_MINUTES = 60;
    static final double[] UPPER_BOUNDS_MS = {1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 80, 100, 120, 150, 200, 250, 300, 400, 500,
            600, 800, 1_000, 1_200, 1_500, 2_000, 2_500, 3_000, 4_000, 5_000, 6_000, 8_000, 10_000, 15_000, 20_000, 30_000, 60_000};
    private static final long MILLIS_PER_MINUTE = TimeUnit.MINUTES.toMillis(1);
    private static final String ACTUATOR = "/actuator";
    private static final double MEDIAN = 0.5;
    private static final double SLOWEST_FIVE_PERCENT = 0.95;

    private final Clock clock;
    private final AtomicReferenceArray<Minute> minutes = new AtomicReferenceArray<>(WINDOW_MINUTES);

    public RecentRequests() {
        this(Clock.systemUTC());
    }

    RecentRequests(final Clock clock) {
        this.clock = clock;
    }

    public static boolean isCounted(final String path) {
        return path != null && !ACTUATOR.equals(path) && !path.startsWith(ACTUATOR + "/");
    }

    public void record(final String path, final int status, final double milliseconds) {
        if (!isCounted(path)) {
            return;
        }
        minuteOf(clock.millis() / MILLIS_PER_MINUTE).add(status, Math.max(0, milliseconds));
    }

    public Window window() {
        final var now = clock.millis() / MILLIS_PER_MINUTE;
        final var latency = new long[UPPER_BOUNDS_MS.length + 1];
        var requests = 0L;
        var redirects = 0L;
        var serverErrors = 0L;
        var slowest = 0.0;
        for (var slot = 0; slot < WINDOW_MINUTES; slot++) {
            final var minute = minutes.get(slot);
            if (minute == null || minute.index <= now - WINDOW_MINUTES || minute.index > now) {
                continue;
            }
            requests += minute.requests.sum();
            redirects += minute.redirects.sum();
            serverErrors += minute.serverErrors.sum();
            slowest = Math.max(slowest, minute.slowest.get());
            for (var bucket = 0; bucket < latency.length; bucket++) {
                latency[bucket] += minute.latency.get(bucket);
            }
        }
        return new Window(WINDOW_MINUTES, requests, redirects, serverErrors, percentile(latency, MEDIAN, slowest),
                percentile(latency, SLOWEST_FIVE_PERCENT, slowest));
    }

    private Minute minuteOf(final long index) {
        final var slot = (int) Math.floorMod(index, WINDOW_MINUTES);
        while (true) {
            final var current = minutes.get(slot);
            if (current != null && current.index >= index) {
                return current;
            }
            final var fresh = new Minute(index);
            if (minutes.compareAndSet(slot, current, fresh)) {
                return fresh;
            }
        }
    }

    private static Double percentile(final long[] latency, final double share, final double slowest) {
        final var total = Arrays.stream(latency).sum();
        if (total == 0) {
            return null;
        }
        final var rank = (long) Math.ceil(share * total);
        var seen = 0L;
        for (var bucket = 0; bucket < latency.length; bucket++) {
            seen += latency[bucket];
            if (seen >= rank) {
                return bucket < UPPER_BOUNDS_MS.length ? Math.min(UPPER_BOUNDS_MS[bucket], slowest) : slowest;
            }
        }
        return slowest;
    }

    private static int bucketOf(final double milliseconds) {
        final var found = Arrays.binarySearch(UPPER_BOUNDS_MS, milliseconds);
        return found >= 0 ? found : -found - 1;
    }

    public record Window(int windowMinutes, long requests, long redirects, long serverErrors, Double p50Ms, Double p95Ms) {
    }

    private static final class Minute {

        private final long index;
        private final LongAdder requests = new LongAdder();
        private final LongAdder redirects = new LongAdder();
        private final LongAdder serverErrors = new LongAdder();
        private final DoubleAccumulator slowest = new DoubleAccumulator(Math::max, 0);
        private final AtomicLongArray latency = new AtomicLongArray(UPPER_BOUNDS_MS.length + 1);

        private Minute(final long index) {
            this.index = index;
        }

        private void add(final int status, final double milliseconds) {
            requests.increment();
            if (status >= 300 && status < 400) {
                redirects.increment();
            } else if (status >= 500 && status < 600) {
                serverErrors.increment();
            }
            slowest.accumulate(milliseconds);
            latency.incrementAndGet(bucketOf(milliseconds));
        }

    }

}
