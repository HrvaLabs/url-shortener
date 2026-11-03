package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class VirusTotalClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String API_KEY_HEADER = "x-apikey";
    private static final String USER_AGENT = "hrva.cc-virustotal/1.0 (+https://hrva.cc/.well-known/security.txt)";
    private static final String URLS_PATH = "/urls";
    private static final String LOOKUP = "lookup";
    private static final String SUBMIT = "submit";
    private static final String NO_ANSWER = "VirusTotal didn't answer.";
    private static final String ERROR_ANSWER = "VirusTotal answered with an error (%d).";

    private final AppProperties appProperties;
    private final JsonMapper jsonMapper;
    private final Duration requestTimeout;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Autowired
    public VirusTotalClient(final AppProperties appProperties, final JsonMapper jsonMapper) {
        this(appProperties, jsonMapper, REQUEST_TIMEOUT);
    }

    VirusTotalClient(final AppProperties appProperties, final JsonMapper jsonMapper, final Duration requestTimeout) {
        this.appProperties = appProperties;
        this.jsonMapper = jsonMapper;
        this.requestTimeout = requestTimeout;
    }

    public VirusTotalReport lookup(final String url) {
        final var apiKey = appProperties.getInspection().getVirusTotalApiKey();
        if (StringUtils.isBlank(apiKey)) {
            log.debug("VirusTotal lookup skipped, the API key is not set");
            return VirusTotalReport.notConfigured();
        }
        return call(LOOKUP, () -> request(apiKey, URLS_PATH + "/" + sha256Of(url)).GET().build(), this::report);
    }

    public VirusTotalReport submit(final String url) {
        final var apiKey = appProperties.getInspection().getVirusTotalApiKey();
        if (StringUtils.isBlank(apiKey)) {
            log.debug("VirusTotal submit skipped, the API key is not set");
            return VirusTotalReport.notConfigured();
        }
        final var form = "url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
        return call(SUBMIT, () -> request(apiKey, URLS_PATH)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build(), this::submitted);
    }

    private VirusTotalReport call(final String kind, final RequestSupplier requestSupplier, final AnswerReader reader) {
        final HttpResponse<String> response;
        try {
            response = exchange(requestSupplier.request());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(kind, 0, NO_ANSWER, exception.getClass().getName());
        } catch (final ExecutionException exception) {
            final var cause = exception.getCause();
            return failed(kind, 0, NO_ANSWER, cause == null ? "unknown" : cause.getClass().getName());
        } catch (final TimeoutException | RuntimeException exception) {
            return failed(kind, 0, NO_ANSWER, exception.getClass().getName());
        }
        final var status = response.statusCode();
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            log.warn("VirusTotal {} refused status={} failure=rate limited", kind, status);
            return VirusTotalReport.rateLimited();
        }
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            return failed(kind, status, VirusTotalReport.KEY_REFUSED, null);
        }
        if (status == HttpStatus.NOT_FOUND.value() && LOOKUP.equals(kind)) {
            log.info("VirusTotal {} answered status={} outcome=not found", kind, status);
            return VirusTotalReport.notFound();
        }
        if (status / 100 != 2) {
            return failed(kind, status, ERROR_ANSWER.formatted(status), null);
        }
        final var report = parse(response.body()).map(reader::read).orElse(null);
        if (report == null) {
            return failed(kind, status, ERROR_ANSWER.formatted(status), null);
        }
        log.info("VirusTotal {} answered status={} outcome={}", kind, status, report.outcome());
        return report;
    }

    private VirusTotalReport report(final JsonNode body) {
        final var data = body.path("data");
        final var reportId = data.path("id").stringValueOpt().filter(StringUtils::isNotBlank);
        if (reportId.isEmpty()) {
            return null;
        }
        final var attributes = data.path("attributes");
        final var stats = attributes.path("last_analysis_stats");
        final var analysedAt = attributes.path("last_analysis_date");
        if (!stats.isObject() || !analysedAt.isIntegralNumber()) {
            return VirusTotalReport.notFound();
        }
        return VirusTotalReport.found(reportId.get(), count(stats, "malicious"), count(stats, "suspicious"), count(stats, "harmless"),
                count(stats, "undetected"), LocalDateTime.ofEpochSecond(analysedAt.asLong(), 0, ZoneOffset.UTC));
    }

    private VirusTotalReport submitted(final JsonNode body) {
        return body.path("data").isObject() ? VirusTotalReport.submitted() : null;
    }

    private static String sha256Of(final String url) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Integer count(final JsonNode stats, final String field) {
        final var value = stats.path(field);
        return value.isIntegralNumber() ? Integer.valueOf(value.asInt()) : Integer.valueOf(0);
    }

    private HttpRequest.Builder request(final String apiKey, final String path) {
        final var base = StringUtils.stripEnd(appProperties.getInspection().getVirusTotalApiBase(), "/");
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(requestTimeout)
                .header(API_KEY_HEADER, apiKey.strip())
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
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

    private Optional<JsonNode> parse(final String body) {
        if (StringUtils.isBlank(body)) {
            return Optional.empty();
        }
        try {
            final var node = jsonMapper.readTree(body);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (final RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static VirusTotalReport failed(final String kind, final int status, final String failure, final String exception) {
        if (exception == null) {
            log.warn("VirusTotal {} failed status={} failure={}", kind, status, failure);
        } else {
            log.warn("VirusTotal {} failed status={} failure={} exception={}", kind, status, failure, exception);
        }
        return VirusTotalReport.failed(failure);
    }

    @FunctionalInterface
    private interface RequestSupplier {

        HttpRequest request();

    }

    @FunctionalInterface
    private interface AnswerReader {

        VirusTotalReport read(JsonNode body);

    }

}
