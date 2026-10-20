package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.GoogleSafeBrowsingApi;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import com.google.api.services.safebrowsing.v5.Safebrowsing;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultSafeBrowsingService implements SafeBrowsingService {

    private final Safebrowsing safebrowsing;
    private final GoogleSafeBrowsingApi googleSafeBrowsingApi;
    private final AppProperties appProperties;

    @Override
    public List<String> checkUrlsForThreats(final List<String> urls) {
        log.debug("SafeBrowsing check count={}", urls.size());
        try {
            final var searchRequest = googleSafeBrowsingApi.createUrlSearchRequest(safebrowsing, urls);
            final var response = googleSafeBrowsingApi.executeSearch(searchRequest);
            return googleSafeBrowsingApi.extractThreatUrls(response);
        } catch (final IOException exception) {
            log.error("ALERT Safe Browsing API unreachable failClosed={} count={}",
                    appProperties.isSafeBrowsingFailClosed(), urls.size(), exception);
            if (appProperties.isSafeBrowsingFailClosed()) {
                throw new UrlValidationException("URL safety check is temporarily unavailable. Please try again later.");
            }
            return List.of();
        }
    }

    @Override
    public List<String> checkUrlsForThreats(final String url) {
        return checkUrlsForThreats(List.of(url));
    }

}
