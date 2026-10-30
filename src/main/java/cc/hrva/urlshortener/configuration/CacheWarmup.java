package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.specification.UrlSpecification;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmup {

    private static final int WARMUP_COUNT = 20;

    private final UrlRepository urlRepository;
    private final CacheManager cacheManager;

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        log.info("Warming up URL cache...");

        final var cache = cacheManager.getCache("urls");
        if (cache == null) {
            log.warn("Cache 'urls' not available, skipping warmup");
            return;
        }

        final var spec = Specification.where(UrlSpecification.hasActive(true));
        var count = 0;

        count += warmupFromQuery(cache, spec, Sort.by(Sort.Direction.DESC, "visits"), "most visited");
        count += warmupFromQuery(cache, spec, Sort.by(Sort.Direction.DESC, "createDate"), "most recent");
        count += warmupFromQuery(cache, spec, Sort.by(Sort.Direction.DESC, "lastAccessed"), "recently accessed");

        log.info("Cache warmup complete — loaded {} entries", count);
    }

    private int warmupFromQuery(
            final org.springframework.cache.Cache cache,
            final Specification<cc.hrva.urlshortener.model.Url> spec,
            final Sort sort,
            final String label) {
        try {
            final var pageable = PageRequest.of(0, WARMUP_COUNT, sort);
            final var urls = urlRepository.findAll(spec, pageable);
            urls.map(UrlResponse::from).forEach(response -> cache.put(response.cacheKey(), response));
            log.info("Cached {} {} URLs", urls.getNumberOfElements(), label);
            return urls.getNumberOfElements();
        } catch (final Exception e) {
            log.warn("Failed to warmup cache for {}", label, e);
            return 0;
        }
    }

}
