package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.dto.stats.CacheStats;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.assertj.core.api.Assertions.assertThat;

class UrlCacheTest {

    private final UrlCache urlCache = new UrlCache(new ConcurrentMapCacheManager("urls"));

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

}
