package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.GoogleSafeBrowsingApi;
import cc.hrva.urlshortener.exception.SafeBrowsingUnavailableException;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpResponseException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DefaultSafeBrowsingService implements SafeBrowsingService {

    private static final String LOOKUPS = "hrva.safe.browsing.lookups";
    private static final String OUTCOME = "outcome";
    private static final Pattern QUERY = Pattern.compile("\\?\\S*");
    private static final String STATUS = "status";

    private final GoogleSafeBrowsingApi googleSafeBrowsingApi;
    private final Counter answered;
    private final Counter failed;

    public DefaultSafeBrowsingService(final GoogleSafeBrowsingApi googleSafeBrowsingApi, final MeterRegistry meterRegistry) {
        this.googleSafeBrowsingApi = googleSafeBrowsingApi;
        this.answered = lookups(meterRegistry, "answered");
        this.failed = lookups(meterRegistry, "failed");
    }

    @Override
    public List<String> checkUrlsForThreats(final List<String> urls) {
        log.debug("SafeBrowsing check count={}", urls.size());
        try {
            final var threats = googleSafeBrowsingApi.threatUrls(urls);
            answered.increment();
            return threats;
        } catch (final IOException | RuntimeException exception) {
            failed.increment();
            final var reason = reasonOf(exception);
            log.error("ALERT Safe Browsing API unreachable count={} reason={}", urls.size(), reason);
            throw new SafeBrowsingUnavailableException(reason);
        }
    }

    @Override
    public List<String> checkUrlsForThreats(final String url) {
        return checkUrlsForThreats(List.of(url));
    }

    private static Counter lookups(final MeterRegistry meterRegistry, final String outcome) {
        return Counter.builder(LOOKUPS)
                .tag(OUTCOME, outcome)
                .description("Safe Browsing lookups: answered, or failed (Google could not be reached or its answer could not be read)")
                .register(meterRegistry);
    }

    private static String reasonOf(final Exception exception) {
        if (exception instanceof final GoogleJsonResponseException json && json.getDetails() != null && json.getDetails().get(STATUS) instanceof final String status) {
            return "HTTP " + json.getStatusCode() + " " + status;
        }
        if (exception instanceof final HttpResponseException http) {
            return "HTTP " + http.getStatusCode();
        }
        return exception.getClass().getSimpleName() + ": " + QUERY.matcher(String.valueOf(exception.getMessage())).replaceAll("");
    }

}
