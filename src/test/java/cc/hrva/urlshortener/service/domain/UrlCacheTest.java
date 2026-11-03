package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.dto.stats.CacheStats;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

    @Test
    void aFailedEvictionIsTriedAgainThirtySecondsLaterThenFiveMinutesLater() {
        final var cache = mock(Cache.class);
        doThrow(new IllegalStateException("redis down")).doThrow(new IllegalStateException("redis down")).doNothing().when(cache).evict("abc");
        final var failingCache = overCache(cache);

        failingCache.evict("abc");

        final var retry = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(retry.capture(), eq(NOW.plusSeconds(30)));
        retry.getValue().run();
        verify(taskScheduler).schedule(retry.capture(), eq(NOW.plusSeconds(300)));
        retry.getValue().run();
        verify(cache, times(3)).evict("abc");
        verifyNoMoreInteractions(taskScheduler);
    }

    @Test
    void aSuccessfulRetryEndsTheRetries() {
        final var cache = mock(Cache.class);
        doThrow(new IllegalStateException("redis down")).doNothing().when(cache).evict("abc");

        overCache(cache).evict("abc");

        final var retry = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(retry.capture(), eq(NOW.plusSeconds(30)));
        retry.getValue().run();
        verify(cache, times(2)).evict("abc");
        verifyNoMoreInteractions(taskScheduler);
    }

    @Test
    void anEvictionThatKeepsFailingStopsAfterTwoRetriesLoggingEachFailureOnce() {
        final var cache = mock(Cache.class);
        doThrow(new IllegalStateException("redis down")).when(cache).evict("abc");
        final var logger = (Logger) LoggerFactory.getLogger(UrlCache.class);
        final var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            overCache(cache).evict("abc");
            final var retry = ArgumentCaptor.forClass(Runnable.class);
            verify(taskScheduler).schedule(retry.capture(), eq(NOW.plusSeconds(30)));
            retry.getValue().run();
            verify(taskScheduler).schedule(retry.capture(), eq(NOW.plusSeconds(300)));
            retry.getValue().run();
        } finally {
            logger.detachAppender(events);
        }

        verify(cache, times(3)).evict("abc");
        verifyNoMoreInteractions(taskScheduler);
        assertThat(events.list).hasSize(3).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertThat(events.list.getLast().getFormattedMessage()).contains("no retries left");
    }

    @Test
    void aFailedSecondEvictionIsTriedAgainToo() {
        final var cache = mock(Cache.class);
        doNothing().doThrow(new IllegalStateException("redis down")).doNothing().when(cache).evict("abc");
        final var failingCache = overCache(cache);

        failingCache.evictNowAndAgainLater("abc");

        final var later = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(later.capture(), eq(NOW.plusSeconds(2)));
        later.getValue().run();
        verify(taskScheduler).schedule(later.capture(), eq(NOW.plusSeconds(30)));
        later.getValue().run();
        verify(cache, times(3)).evict("abc");
        verifyNoMoreInteractions(taskScheduler);
    }

    @Test
    void aFailedClearIsTriedAgainToo() {
        final var cache = mock(Cache.class);
        doThrow(new IllegalStateException("redis down")).doNothing().when(cache).clear();

        overCache(cache).clearNowAndAgainLater();

        final var later = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(later.capture(), eq(NOW.plusSeconds(30)));
        verify(taskScheduler).schedule(later.capture(), eq(NOW.plusSeconds(2)));
        verify(cache, times(1)).clear();
    }

    @Test
    void twoHundredFailingEvictionsScheduleAtMostFiftyKeyRetriesAndOneFullClearOfEachTiming() {
        final var cache = mock(Cache.class);
        doThrow(new IllegalStateException("redis down")).when(cache).evict(anyString());
        final var failingCache = overCache(cache);

        for (var index = 0; index < 200; index++) {
            failingCache.evict("link" + index);
        }

        final var atThirtySeconds = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler, times(51)).schedule(atThirtySeconds.capture(), eq(NOW.plusSeconds(30)));
        final var atFiveMinutes = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler, times(1)).schedule(atFiveMinutes.capture(), eq(NOW.plusSeconds(300)));
        atFiveMinutes.getValue().run();
        verify(cache).clear();
        atThirtySeconds.getAllValues().forEach(Runnable::run);
        verify(cache, times(2)).clear();
        verify(cache, times(250)).evict(anyString());
    }

    @Test
    void aFullClearIsScheduledAgainOnlyAfterThePendingOneRan() {
        final var cache = mock(Cache.class);
        doThrow(new IllegalStateException("redis down")).when(cache).evict(anyString());
        final var failingCache = overCache(cache);
        for (var index = 0; index < 51; index++) {
            failingCache.evict("link" + index);
        }
        final var atFiveMinutes = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(atFiveMinutes.capture(), eq(NOW.plusSeconds(300)));

        failingCache.evict("another");
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), eq(NOW.plusSeconds(300)));

        atFiveMinutes.getValue().run();
        failingCache.evict("after the clear");
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), eq(NOW.plusSeconds(300)));
    }

    private UrlCache overCache(final Cache cache) {
        final var manager = mock(CacheManager.class);
        when(manager.getCache(UrlCache.CACHE_NAME)).thenReturn(cache);
        return new UrlCache(manager, taskScheduler, Clock.fixed(NOW, ZoneOffset.UTC));
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
