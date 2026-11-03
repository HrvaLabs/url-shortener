package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.dto.stats.CacheStats;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class UrlCache {

    public static final String CACHE_NAME = "urls";

    private static final Duration SECOND_EVICTION_DELAY = Duration.ofSeconds(2);
    private static final List<Duration> RETRIES_AFTER_A_FAILURE = List.of(Duration.ofSeconds(30), Duration.ofMinutes(5));

    private final CacheManager cacheManager;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final LongAdder redirectHits = new LongAdder();
    private final LongAdder redirectMisses = new LongAdder();

    public Object get(final String cacheKey) {
        try {
            return Optional.ofNullable(cacheManager.getCache(CACHE_NAME))
                    .map(cache -> cache.get(cacheKey))
                    .map(Cache.ValueWrapper::get)
                    .orElse(null);
        } catch (final RuntimeException exception) {
            log.warn("URL cache read failed, using the database key={} exception={}", cacheKey, exception.getClass().getName());
            return null;
        }
    }

    public void put(final String cacheKey, final Object value) {
        try {
            final var cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                cache.put(cacheKey, value);
            }
        } catch (final RuntimeException exception) {
            log.warn("URL cache write failed key={} exception={}", cacheKey, exception.getClass().getName());
        }
    }

    public void evict(final String cacheKey) {
        evict(cacheKey, RETRIES_AFTER_A_FAILURE);
    }

    public void evictNowAndAgainLater(final String cacheKey) {
        afterCommit(() -> {
            evict(cacheKey);
            later(() -> evict(cacheKey), SECOND_EVICTION_DELAY);
        });
    }

    public void clearNowAndAgainLater() {
        afterCommit(() -> {
            clear(RETRIES_AFTER_A_FAILURE);
            later(() -> clear(RETRIES_AFTER_A_FAILURE), SECOND_EVICTION_DELAY);
        });
    }

    public void recordRedirectHit() {
        redirectHits.increment();
    }

    public void recordRedirectMiss() {
        redirectMisses.increment();
    }

    public CacheStats redirectStats() {
        return CacheStats.of(redirectHits.sum(), redirectMisses.sum());
    }

    private void evict(final String cacheKey, final List<Duration> retries) {
        try {
            final var cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                cache.evict(cacheKey);
            }
        } catch (final RuntimeException exception) {
            if (retries.isEmpty()) {
                log.warn("URL cache eviction failed, no retries left key={} exception={}", cacheKey, exception.getClass().getName());
                return;
            }
            log.warn("URL cache eviction failed, retrying in {} s key={} exception={}", retries.getFirst().toSeconds(), cacheKey,
                    exception.getClass().getName());
            later(() -> evict(cacheKey, retries.subList(1, retries.size())), retries.getFirst());
        }
    }

    private void clear(final List<Duration> retries) {
        try {
            final var cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                cache.clear();
            }
        } catch (final RuntimeException exception) {
            if (retries.isEmpty()) {
                log.warn("URL cache clear failed, no retries left exception={}", exception.getClass().getName());
                return;
            }
            log.warn("URL cache clear failed, retrying in {} s exception={}", retries.getFirst().toSeconds(), exception.getClass().getName());
            later(() -> clear(retries.subList(1, retries.size())), retries.getFirst());
        }
    }

    private void later(final Runnable eviction, final Duration delay) {
        try {
            taskScheduler.schedule(eviction, clock.instant().plus(delay));
        } catch (final RuntimeException exception) {
            log.warn("Scheduling a URL cache eviction failed exception={}", exception.getClass().getName());
        }
    }

    private static void afterCommit(final Runnable eviction) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            eviction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eviction.run();
            }
        });
    }

}
