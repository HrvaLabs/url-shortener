package cc.hrva.urlshortener.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordWebhookClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(10);
    private static final String RETRY_AFTER = "retry_after";
    private static final String USER_AGENT = "hrva.cc-alerts/1.0 (+https://hrva.cc/.well-known/security.txt)";
    private static final double MILLIS_PER_SECOND = 1000.0;

    private final JsonMapper jsonMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public boolean send(final String webhookUrl, final DiscordMessage message, final Duration timeout) {
        return send(webhookUrl, message, timeout, true);
    }

    public boolean sendWithoutWaiting(final String webhookUrl, final DiscordMessage message, final Duration timeout) {
        return send(webhookUrl, message, timeout, false);
    }

    private boolean send(final String webhookUrl, final DiscordMessage message, final Duration timeout, final boolean mayWaitOutRateLimit) {
        try {
            final var request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(timeout)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(message), StandardCharsets.UTF_8))
                    .build();
            var response = exchange(request, timeout);
            if (mayWaitOutRateLimit && response.statusCode() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                final var retryAfter = retryAfter(response).filter(wait -> wait.compareTo(MAX_RETRY_AFTER) < 0);
                if (retryAfter.isPresent()) {
                    Thread.sleep(retryAfter.get());
                    response = exchange(request, timeout);
                }
            }
            if (response.statusCode() / 100 == 2) {
                return true;
            }
            log.warn("Discord webhook refused the alerts, dropping them status={}", response.statusCode());
            return false;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Discord webhook call was interrupted, dropping the alerts exception={}", exception.getClass().getName());
            return false;
        } catch (final ExecutionException exception) {
            log.warn("Discord webhook call failed, dropping the alerts exception={}", exception.getCause().getClass().getName());
            return false;
        } catch (final TimeoutException | RuntimeException exception) {
            log.warn("Discord webhook call failed, dropping the alerts exception={}", exception.getClass().getName());
            return false;
        }
    }

    private HttpResponse<String> exchange(final HttpRequest request, final Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        final var response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | TimeoutException exception) {
            response.cancel(true);
            throw exception;
        }
    }

    private Optional<Duration> retryAfter(final HttpResponse<String> response) {
        try {
            final var inBody = jsonMapper.readTree(response.body()).path(RETRY_AFTER);
            if (inBody.isNumber()) {
                return Optional.of(seconds(inBody.asDouble()));
            }
        } catch (final RuntimeException exception) {
            log.debug("Discord rate limit answer had no readable body exception={}", exception.getClass().getName());
        }
        try {
            return response.headers().firstValue(HttpHeaders.RETRY_AFTER).map(Double::parseDouble).map(DiscordWebhookClient::seconds);
        } catch (final NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static Duration seconds(final double seconds) {
        return Duration.ofMillis(Math.max(0, Math.round(seconds * MILLIS_PER_SECOND)));
    }

}
