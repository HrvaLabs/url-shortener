package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.PageFetcher;
import cc.hrva.urlshortener.dto.LinkPreviewResponse;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.LinkPreviewOutcome;
import cc.hrva.urlshortener.service.LinkPreviewOutcome.Busy;
import cc.hrva.urlshortener.service.LinkPreviewOutcome.Limited;
import cc.hrva.urlshortener.service.LinkPreviewOutcome.Ready;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.util.DomainNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class DefaultLinkPreviewService implements LinkPreviewService {

    private static final Logger log = LoggerFactory.getLogger(DefaultLinkPreviewService.class);
    private static final long MAX_PAGE_BYTES = 2L * 1024L * 1024L;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String CACHE_KEY_PREFIX = "link-preview:";
    private static final Duration CACHE_TIME_TO_LIVE = Duration.ofMinutes(10);
    private static final String NETWORK_LIMIT_NAME = "link-preview-network";
    private static final int PREVIEWS_PER_NETWORK = 30;
    private static final Duration NETWORK_WINDOW = Duration.ofMinutes(1);
    private static final String LINK_LIMIT_PREFIX = "link-preview-link:";
    private static final int FETCHES_PER_LINK = 10;
    private static final Duration LINK_WINDOW = Duration.ofMinutes(10);
    private static final int CONCURRENT_FETCHES = 8;

    private final Semaphore fetchSlots = new Semaphore(CONCURRENT_FETCHES);
    private final PageFetcher pageFetcher;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public LinkPreviewOutcome preview(final long linkId, final String url, final String clientIp) {
        try {
            return limitedPreview(linkId, url, clientIp);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Link preview rate limit unavailable, refusing the preview linkId={}", linkId);
            return new Busy();
        }
    }

    private LinkPreviewOutcome limitedPreview(final long linkId, final String url, final String clientIp) {
        final var networkWait = wait(RateLimiter.clientKey(NETWORK_LIMIT_NAME, clientIp), PREVIEWS_PER_NETWORK, NETWORK_WINDOW);
        if (networkWait.isPresent()) {
            return new Limited(networkWait.get());
        }
        final var cacheKey = CACHE_KEY_PREFIX + sha256(url);
        final var cached = cached(cacheKey, linkId);
        if (cached.isPresent()) {
            return new Ready(cached.get());
        }
        final var linkWait = wait(LINK_LIMIT_PREFIX + linkId, FETCHES_PER_LINK, LINK_WINDOW);
        if (linkWait.isPresent()) {
            return new Limited(linkWait.get());
        }
        if (!fetchSlots.tryAcquire()) {
            log.info("Link preview turned away because every fetch slot is busy linkId={} domain={}", linkId, domainOf(url));
            return new Busy();
        }
        try {
            final var preview = fetchPreview(linkId, url);
            store(cacheKey, preview, linkId);
            return new Ready(preview);
        } finally {
            fetchSlots.release();
        }
    }

    private LinkPreviewResponse fetchPreview(final long linkId, final String url) {
        try {
            final var page = pageFetcher.fetch(url, MAX_PAGE_BYTES, TIMEOUT);
            try {
                final var doc = Jsoup.parse(page.body().toFile(), null, url);
                return new LinkPreviewResponse(
                        url,
                        extractMeta(doc, "og:title", "twitter:title"),
                        extractMeta(doc, "og:description", "description", "twitter:description"),
                        extractMeta(doc, "og:image", "twitter:image")
                );
            } finally {
                Files.deleteIfExists(page.body());
            }
        } catch (final UrlValidationException exception) {
            log.info("Link preview fetch failed linkId={} domain={} reason={}", linkId, domainOf(url), exception.getMessage());
        } catch (final IOException | RuntimeException exception) {
            log.warn("Link preview fetch failed linkId={} domain={} exception={}", linkId, domainOf(url), exception.getClass().getName());
        }
        return new LinkPreviewResponse(url, null, null, null);
    }

    private Optional<Duration> wait(final String key, final int limit, final Duration window) {
        final var counted = rateLimiter.incrementWindow(key, window);
        return counted.count() > limit ? Optional.of(counted.resetsIn()) : Optional.empty();
    }

    private Optional<LinkPreviewResponse> cached(final String key, final long linkId) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key)).map(json -> jsonMapper.readValue(json, LinkPreviewResponse.class));
        } catch (final RuntimeException exception) {
            log.warn("Link preview cache read failed linkId={} exception={}", linkId, exception.getClass().getName());
            return Optional.empty();
        }
    }

    private void store(final String key, final LinkPreviewResponse preview, final long linkId) {
        try {
            redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(preview), CACHE_TIME_TO_LIVE);
        } catch (final RuntimeException exception) {
            log.warn("Link preview cache write failed linkId={} exception={}", linkId, exception.getClass().getName());
        }
    }

    private static String domainOf(final String url) {
        return DomainNames.hostOf(url).orElse("-");
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String extractMeta(final Document doc, final String... metaNames) {
        for (final var name : metaNames) {
            final var tag = doc.selectFirst("meta[name=" + name + "], meta[property=" + name + "]");
            if (tag != null) {
                final var content = tag.attr("content");
                if (!content.isBlank()) {
                    return content;
                }
            }
        }

        final var title = doc.title();
        return title.isBlank() ? null : title;
    }

}
