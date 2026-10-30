package cc.hrva.urlshortener.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import cc.hrva.urlshortener.client.GoogleSafeBrowsingApi;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.impl.DefaultSafeBrowsingService;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import com.google.api.services.safebrowsing.v5.Safebrowsing;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultSafeBrowsingServiceTest {

    private static final String DESTINATION = "https://secret-destination.example/path";

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(DefaultSafeBrowsingService.class);

    @Mock
    private Safebrowsing safebrowsing;

    @Mock
    private GoogleSafeBrowsingApi googleSafeBrowsingApi;

    private AppProperties appProperties;

    private DefaultSettingsService settingsService;

    private Level previousLevel;

    private SafeBrowsingService safeBrowsingService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        settingsService = SettingsServiceFixtures.defaults(appProperties);
        safeBrowsingService = new DefaultSafeBrowsingService(safebrowsing, googleSafeBrowsingApi, settingsService);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLogLevel() {
        serviceLogger.setLevel(previousLevel);
    }

    @Test
    void checkLogsCountButNoUrl(final CapturedOutput output) throws IOException {
        when(googleSafeBrowsingApi.createUrlSearchRequest(any(), any())).thenReturn(null);
        when(googleSafeBrowsingApi.executeSearch(any())).thenReturn(null);
        when(googleSafeBrowsingApi.extractThreatUrls(any())).thenReturn(List.of());

        final var threats = safeBrowsingService.checkUrlsForThreats(DESTINATION);

        assertThat(threats).isEmpty();
        assertThat(output)
                .contains("SafeBrowsing check count=1")
                .doesNotContain("secret-destination.example");
    }

    @Test
    void unreachableApiAlertLogsCountButNoUrl(final CapturedOutput output) throws IOException {
        appProperties.setSafeBrowsingFailClosed(true);
        when(googleSafeBrowsingApi.createUrlSearchRequest(any(), any())).thenReturn(null);
        when(googleSafeBrowsingApi.executeSearch(any())).thenThrow(new IOException("Connection refused"));

        assertThatThrownBy(() -> safeBrowsingService.checkUrlsForThreats(DESTINATION))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("URL safety check is temporarily unavailable. Please try again later.");
        assertThat(output)
                .contains("ALERT Safe Browsing API unreachable failClosed=true count=1")
                .contains("java.io.IOException: Connection refused")
                .doesNotContain("secret-destination.example");
    }

    @Test
    void failClosedFollowsTheSetting() throws IOException {
        when(googleSafeBrowsingApi.executeSearch(any())).thenThrow(new IOException("Connection refused"));

        assertThat(safeBrowsingService.checkUrlsForThreats(DESTINATION)).isEmpty();

        SettingsServiceFixtures.turnOn(settingsService, SettingKey.SAFE_BROWSING_FAIL_CLOSED);

        assertThatThrownBy(() -> safeBrowsingService.checkUrlsForThreats(DESTINATION))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("URL safety check is temporarily unavailable. Please try again later.");
    }

    @Test
    void failClosedNeedsTheSafeBrowsingCheck(final CapturedOutput output) throws IOException {
        appProperties.setSafeBrowsingFailClosed(true);
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.SAFE_BROWSING_CHECK);
        when(googleSafeBrowsingApi.executeSearch(any())).thenThrow(new IOException("Connection refused"));

        assertThat(safeBrowsingService.checkUrlsForThreats(DESTINATION)).isEmpty();
        assertThat(output).contains("ALERT Safe Browsing API unreachable failClosed=false count=1");
    }

}
