package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.PageFetcher;
import cc.hrva.urlshortener.dto.LinkPreviewResponse;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.util.DomainNames;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultLinkPreviewService implements LinkPreviewService {

    private static final Logger log = LoggerFactory.getLogger(DefaultLinkPreviewService.class);
    private static final long MAX_PAGE_BYTES = 2L * 1024L * 1024L;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final PageFetcher pageFetcher;

    @Override
    public LinkPreviewResponse fetchPreview(final String url) {
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
        } catch (final UrlValidationException | IOException exception) {
            log.warn("Failed to fetch preview domain={} reason={}", DomainNames.hostOf(url).orElse("-"), exception.getMessage());
            return new LinkPreviewResponse(url, null, null, null);
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
