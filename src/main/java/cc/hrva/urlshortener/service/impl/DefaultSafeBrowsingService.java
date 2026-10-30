package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.GoogleSafeBrowsingApi;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
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
    private final SettingsService settingsService;

    @Override
    public List<String> checkUrlsForThreats(final List<String> urls) {
        log.debug("SafeBrowsing check count={}", urls.size());
        try {
            final var searchRequest = googleSafeBrowsingApi.createUrlSearchRequest(safebrowsing, urls);
            final var response = googleSafeBrowsingApi.executeSearch(searchRequest);
            return googleSafeBrowsingApi.extractThreatUrls(response);
        } catch (final IOException exception) {
            final var failClosed = settingsService.isOn(SettingKey.SAFE_BROWSING_CHECK) && settingsService.isOn(SettingKey.SAFE_BROWSING_FAIL_CLOSED);
            log.error("ALERT Safe Browsing API unreachable failClosed={} count={}", failClosed, urls.size(), exception);
            if (failClosed) {
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
