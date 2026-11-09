package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.AnalyticsEngineException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class AnalyticsEngineClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "hrva.cc-visits/1.0 (+https://hrva.cc/.well-known/security.txt)";
    private static final String BEARER = "Bearer ";
    private static final String SQL_PATH = "/analytics_engine/sql";
    private static final String FORMAT_JSON = " FORMAT JSON";
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {
    };

    private final AppProperties appProperties;
    private final JsonMapper jsonMapper;
    private final Duration requestTimeout;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Autowired
    public AnalyticsEngineClient(final AppProperties appProperties, final JsonMapper jsonMapper) {
        this(appProperties, jsonMapper, REQUEST_TIMEOUT);
    }

    AnalyticsEngineClient(final AppProperties appProperties, final JsonMapper jsonMapper, final Duration requestTimeout) {
        this.appProperties = appProperties;
        this.jsonMapper = jsonMapper;
        this.requestTimeout = requestTimeout;
    }

    public boolean configured() {
        final var settings = appProperties.getCloudflare();
        return StringUtils.isNoneBlank(settings.getAccountId(), settings.getAnalyticsApiToken());
    }

    public List<Map<String, Object>> query(final String sql) {
        final var settings = appProperties.getCloudflare();
        final HttpRequest request;
        try {
            request = request(sql, settings);
        } catch (final RuntimeException exception) {
            throw failed("Analytics Engine request could not be built", exception);
        }
        final HttpResponse<String> response;
        try {
            response = exchange(request);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failed("Analytics Engine call interrupted", exception);
        } catch (final TimeoutException exception) {
            throw failed("Analytics Engine did not answer in time", exception);
        } catch (final ExecutionException | RuntimeException exception) {
            throw failed("Analytics Engine did not answer", exception);
        }
        if (response.statusCode() / 100 != 2) {
            log.warn("Analytics Engine call refused status={}", response.statusCode());
            throw new AnalyticsEngineException("Analytics Engine answered " + response.statusCode());
        }
        return rows(response.body());
    }

    private HttpRequest request(final String sql, final AppProperties.Cloudflare settings) {
        final var base = StringUtils.stripEnd(settings.getApiBase(), "/");
        final var endpoint = base + "/accounts/" + URLEncoder.encode(settings.getAccountId().strip(), StandardCharsets.UTF_8) + SQL_PATH;
        return HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(requestTimeout)
                .header(HttpHeaders.AUTHORIZATION, BEARER + settings.getAnalyticsApiToken().strip())
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(sql + FORMAT_JSON, StandardCharsets.UTF_8))
                .build();
    }

    private HttpResponse<String> exchange(final HttpRequest request) throws InterruptedException, ExecutionException, TimeoutException {
        final var response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return response.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | TimeoutException exception) {
            response.cancel(true);
            throw exception;
        }
    }

    private List<Map<String, Object>> rows(final String body) {
        try {
            final var data = jsonMapper.readTree(StringUtils.defaultString(body)).path("data");
            if (!data.isArray()) {
                log.warn("Analytics Engine answer had no data");
                throw new AnalyticsEngineException("Analytics Engine answer had no data");
            }
            return jsonMapper.convertValue(data, ROWS);
        } catch (final AnalyticsEngineException exception) {
            throw exception;
        } catch (final RuntimeException exception) {
            throw failed("Analytics Engine answer could not be read", exception);
        }
    }

    private static AnalyticsEngineException failed(final String message, final Throwable cause) {
        log.warn("Analytics Engine call failed failure={} exception={}", message, cause.getClass().getName());
        return new AnalyticsEngineException(message);
    }

}
