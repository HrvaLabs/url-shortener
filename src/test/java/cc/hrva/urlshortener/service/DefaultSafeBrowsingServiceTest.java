package cc.hrva.urlshortener.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import cc.hrva.urlshortener.client.GoogleSafeBrowsingApi;
import cc.hrva.urlshortener.configuration.properties.GoogleApiProperties;
import cc.hrva.urlshortener.exception.SafeBrowsingUnavailableException;
import cc.hrva.urlshortener.service.impl.DefaultSafeBrowsingService;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.safebrowsing.v5.Safebrowsing;
import com.google.gson.stream.MalformedJsonException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
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
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultSafeBrowsingServiceTest {

    private static final String DESTINATION = "https://secret-destination.example/path";
    private static final String KEY = "secret-api-key";

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(DefaultSafeBrowsingService.class);

    @Mock
    private GoogleSafeBrowsingApi googleSafeBrowsingApi;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private Level previousLevel;

    @BeforeEach
    void raiseLogLevel() {
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLogLevel() {
        serviceLogger.setLevel(previousLevel);
    }

    @Test
    void checkReturnsTheThreatsAndLogsTheCountButNoUrl(final CapturedOutput output) throws IOException {
        when(googleSafeBrowsingApi.threatUrls(List.of(DESTINATION))).thenReturn(List.of("secret-destination.example/path"));

        final var threats = new DefaultSafeBrowsingService(googleSafeBrowsingApi, meterRegistry).checkUrlsForThreats(DESTINATION);

        assertThat(threats).containsExactly("secret-destination.example/path");
        assertThat(output)
                .contains("SafeBrowsing check count=1")
                .doesNotContain("secret-destination.example");
    }

    @Test
    void failureIsAlwaysThrownSoCallersNeverMistakeItForACleanResult(final CapturedOutput output) throws IOException {
        when(googleSafeBrowsingApi.threatUrls(List.of(DESTINATION))).thenThrow(new IOException("Connection refused"));

        assertThatThrownBy(() -> new DefaultSafeBrowsingService(googleSafeBrowsingApi, meterRegistry).checkUrlsForThreats(DESTINATION))
                .isInstanceOf(SafeBrowsingUnavailableException.class)
                .hasMessage("IOException: Connection refused");
        assertThat(output)
                .contains("ALERT Safe Browsing API unreachable count=1 reason=IOException: Connection refused")
                .doesNotContain("secret-destination.example")
                .doesNotContain("\tat ");
    }

    @Test
    void unreadableAnswerIsAFailure(final CapturedOutput output) throws IOException {
        when(googleSafeBrowsingApi.threatUrls(List.of(DESTINATION)))
                .thenThrow(new MalformedJsonException("Use JsonReader.setStrictness(Strictness.LENIENT) to accept malformed JSON at line 1 column 1 path $"));

        assertThatThrownBy(() -> new DefaultSafeBrowsingService(googleSafeBrowsingApi, meterRegistry).checkUrlsForThreats(DESTINATION))
                .isInstanceOf(SafeBrowsingUnavailableException.class);
        assertThat(output).contains("reason=MalformedJsonException: Use JsonReader.setStrictness");
    }

    @Test
    void unexpectedRuntimeFailureIsAFailureToo() throws IOException {
        when(googleSafeBrowsingApi.threatUrls(List.of(DESTINATION))).thenThrow(new IllegalArgumentException("bad media type"));

        assertThatThrownBy(() -> new DefaultSafeBrowsingService(googleSafeBrowsingApi, meterRegistry).checkUrlsForThreats(DESTINATION))
                .isInstanceOf(SafeBrowsingUnavailableException.class)
                .hasMessage("IllegalArgumentException: bad media type");
    }

    @Test
    void googleErrorIsLoggedByStatusWithoutTheRequestAddressItsKeyOrTheDestination(final CapturedOutput output) {
        final var error = """
                {"error": {"code": 400, "message": "Invalid URL: https://secret-destination.example/path", "status": "INVALID_ARGUMENT"}}""";
        final var service = new DefaultSafeBrowsingService(apiAnswering(new MockLowLevelHttpResponse()
                .setStatusCode(400)
                .setContentType("application/json; charset=UTF-8")
                .setContent(error)), meterRegistry);

        assertThatThrownBy(() -> service.checkUrlsForThreats(DESTINATION))
                .isInstanceOf(SafeBrowsingUnavailableException.class)
                .hasMessage("HTTP 400 INVALID_ARGUMENT");
        assertThat(output)
                .contains("ALERT Safe Browsing API unreachable count=1 reason=HTTP 400 INVALID_ARGUMENT")
                .doesNotContain(KEY)
                .doesNotContain("secret-destination.example")
                .doesNotContain("safebrowsing.googleapis.com");
    }

    @Test
    void errorWithoutAGoogleBodyIsLoggedByStatusOnly(final CapturedOutput output) {
        final var service = new DefaultSafeBrowsingService(apiAnswering(new MockLowLevelHttpResponse()
                .setStatusCode(502)
                .setContentType("text/html")
                .setContent("<html>Bad gateway</html>")), meterRegistry);

        assertThatThrownBy(() -> service.checkUrlsForThreats(DESTINATION))
                .isInstanceOf(SafeBrowsingUnavailableException.class)
                .hasMessage("HTTP 502");
        assertThat(output).doesNotContain(KEY).doesNotContain("secret-destination.example");
    }

    @Test
    void addressesInAnErrorMessageLoseTheirQuery() throws IOException {
        when(googleSafeBrowsingApi.threatUrls(List.of(DESTINATION)))
                .thenThrow(new IOException("Server returned HTTP response code: 403 for URL: https://safebrowsing.googleapis.com/v5/urls:search?key=" + KEY + "&urls=x"));

        assertThatThrownBy(() -> new DefaultSafeBrowsingService(googleSafeBrowsingApi, meterRegistry).checkUrlsForThreats(DESTINATION))
                .isInstanceOf(SafeBrowsingUnavailableException.class)
                .hasMessage("IOException: Server returned HTTP response code: 403 for URL: https://safebrowsing.googleapis.com/v5/urls:search");
    }

    @Test
    void lookupsAreCountedByOutcomeAndBothCountsExistBeforeTheFirstLookup() throws IOException {
        final var service = new DefaultSafeBrowsingService(googleSafeBrowsingApi, meterRegistry);
        assertThat(lookups("answered")).isZero();
        assertThat(lookups("failed")).isZero();
        when(googleSafeBrowsingApi.threatUrls(List.of(DESTINATION))).thenReturn(List.of()).thenThrow(new IOException("Read timed out"));

        service.checkUrlsForThreats(DESTINATION);
        assertThatThrownBy(() -> service.checkUrlsForThreats(DESTINATION)).isInstanceOf(SafeBrowsingUnavailableException.class);

        assertThat(lookups("answered")).isEqualTo(1);
        assertThat(lookups("failed")).isEqualTo(1);
    }

    @Test
    void prometheusExportsBothLookupSeriesUnderTheNamesTheGrafanaRulesRead() throws IOException {
        final var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        final var service = new DefaultSafeBrowsingService(googleSafeBrowsingApi, prometheus);
        assertThat(prometheus.scrape())
                .contains("hrva_safe_browsing_lookups_total{outcome=\"answered\"} 0.0")
                .contains("hrva_safe_browsing_lookups_total{outcome=\"failed\"} 0.0");
        when(googleSafeBrowsingApi.threatUrls(List.of(DESTINATION))).thenReturn(List.of());

        service.checkUrlsForThreats(DESTINATION);

        assertThat(prometheus.scrape()).contains("hrva_safe_browsing_lookups_total{outcome=\"answered\"} 1.0");
    }

    private double lookups(final String outcome) {
        return meterRegistry.get("hrva.safe.browsing.lookups").tag("outcome", outcome).counter().count();
    }

    private static GoogleSafeBrowsingApi apiAnswering(final MockLowLevelHttpResponse response) {
        final var properties = new GoogleApiProperties();
        properties.setKey(KEY);
        final var transport = new MockHttpTransport.Builder().setLowLevelHttpResponse(response).build();
        final var safebrowsing = new Safebrowsing.Builder(transport, GsonFactory.getDefaultInstance(), null).setApplicationName("hrva.cc").build();
        return new GoogleSafeBrowsingApi(properties, safebrowsing);
    }

}
