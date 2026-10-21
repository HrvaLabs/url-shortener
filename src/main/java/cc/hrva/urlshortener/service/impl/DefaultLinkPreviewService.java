package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkPreviewResponse;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.util.PublicAddresses;
import java.net.URI;
import java.time.Duration;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultLinkPreviewService implements LinkPreviewService {

    private static final Logger log = LoggerFactory.getLogger(DefaultLinkPreviewService.class);
    private static final int MAX_REDIRECTS = 3;

    @Override
    public LinkPreviewResponse fetchPreview(final String url) {
        try {
            var target = url;
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                if (!isPublicTarget(target)) {
                    log.warn("Refused preview of a non-public address");
                    return new LinkPreviewResponse(url, null, null, null);
                }

                final var response = Jsoup.connect(target)
                        .timeout((int) Duration.ofSeconds(5).toMillis())
                        .followRedirects(false)
                        .ignoreHttpErrors(true)
                        .userAgent("Mozilla/5.0 (compatible; UrlShortenerBot/1.0)")
                        .execute();

                final var location = response.header("Location");
                if (response.statusCode() / 100 == 3 && location != null) {
                    target = URI.create(target).resolve(location).toString();
                    continue;
                }

                final var doc = response.parse();
                return new LinkPreviewResponse(
                        url,
                        extractMeta(doc, "og:title", "twitter:title"),
                        extractMeta(doc, "og:description", "description", "twitter:description"),
                        extractMeta(doc, "og:image", "twitter:image")
                );
            }
            return new LinkPreviewResponse(url, null, null, null);
        } catch (final Exception exception) {
            log.warn("Failed to fetch preview for url={}", url, exception);
            return new LinkPreviewResponse(url, null, null, null);
        }
    }

    public static boolean isPublicTarget(final String url) {
        return PublicAddresses.isPublicTarget(url);
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
