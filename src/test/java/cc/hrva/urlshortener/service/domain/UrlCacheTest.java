package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.dto.stats.CacheStats;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UrlCacheTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(UrlCache.CACHE_NAME);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final UrlCache urlCache = new UrlCache(cacheManager, taskScheduler, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void endTheTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void redirectStatsCountHitsAndMisses() {
        urlCache.recordRedirectHit();
        urlCache.recordRedirectMiss();
        urlCache.recordRedirectMiss();
        urlCache.recordRedirectMiss();

        assertThat(urlCache.redirectStats()).isEqualTo(new CacheStats(1, 3, 25.0));
    }

    @Test
    void noRedirectsHaveNoRatio() {
        assertThat(urlCache.redirectStats()).isEqualTo(new CacheStats(0, 0, null));
    }

    @Test
    void readingAndWritingTheCacheCountsNothing() {
        urlCache.put("abc", "value");
        urlCache.get("abc");
        urlCache.get("nope");
        urlCache.evict("abc");

        assertThat(urlCache.redirectStats()).isEqualTo(new CacheStats(0, 0, null));
    }

    @Test
    void outsideATransactionALinkIsEvictedAtOnceAndAgainTwoSecondsLater() {
        urlCache.put("abc", "value");

        urlCache.evictNowAndAgainLater("abc");

        assertThat(urlCache.get("abc")).isNull();
        final var later = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(later.capture(), eq(NOW.plusSeconds(2)));
        urlCache.put("abc", "put back by a racing click");
        later.getValue().run();
        assertThat(urlCache.get("abc")).isNull();
    }

    @Test
    void insideATransactionALinkIsEvictedOnlyAfterTheCommitAndAgainLater() {
        urlCache.put("abc", "value");
        inATransaction();

        urlCache.evictNowAndAgainLater("abc");

        assertThat(urlCache.get("abc")).isEqualTo("value");
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        commit();
        assertThat(urlCache.get("abc")).isNull();
        final var later = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(later.capture(), eq(NOW.plusSeconds(2)));
        urlCache.put("abc", "put back by a racing click");
        later.getValue().run();
        assertThat(urlCache.get("abc")).isNull();
    }

    @Test
    void aRolledBackTransactionEvictsNothing() {
        urlCache.put("abc", "value");
        inATransaction();

        urlCache.evictNowAndAgainLater("abc");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(urlCache.get("abc")).isEqualTo("value");
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void clearingEmptiesTheCacheAfterTheCommitAndAgainLater() {
        urlCache.put("abc", "value");
        urlCache.put("7/menu", "value");
        inATransaction();

        urlCache.clearNowAndAgainLater();

        assertThat(urlCache.get("abc")).isEqualTo("value");
        commit();
        assertThat(urlCache.get("abc")).isNull();
        assertThat(urlCache.get("7/menu")).isNull();
        final var later = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(later.capture(), eq(NOW.plusSeconds(2)));
        urlCache.put("abc", "put back by a racing click");
        later.getValue().run();
        assertThat(urlCache.get("abc")).isNull();
    }

    @Test
    void theFirstEvictionStandsWhenTheSecondCannotBeScheduled() {
        urlCache.put("abc", "value");
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenThrow(new TaskRejectedException("shutting down"));

        assertThatCode(() -> urlCache.evictNowAndAgainLater("abc")).doesNotThrowAnyException();
        assertThatCode(urlCache::clearNowAndAgainLater).doesNotThrowAnyException();

        assertThat(urlCache.get("abc")).isNull();
    }

    private static void inATransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void commit() {
        final var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }

}
