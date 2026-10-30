package cc.hrva.urlshortener.service.domain;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UrlCache {

    private static final String URLS_CACHE = "urls";

    private final CacheManager cacheManager;

    public Object get(final String cacheKey) {
        try {
            return Optional.ofNullable(cacheManager.getCache(URLS_CACHE))
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
            final var cache = cacheManager.getCache(URLS_CACHE);
            if (cache != null) {
                cache.put(cacheKey, value);
            }
        } catch (final RuntimeException exception) {
            log.warn("URL cache write failed key={} exception={}", cacheKey, exception.getClass().getName());
        }
    }

    public void evict(final String cacheKey) {
        try {
            final var cache = cacheManager.getCache(URLS_CACHE);
            if (cache != null) {
                cache.evict(cacheKey);
            }
        } catch (final RuntimeException exception) {
            log.warn("URL cache eviction failed key={} exception={}", cacheKey, exception.getClass().getName());
        }
    }

}
