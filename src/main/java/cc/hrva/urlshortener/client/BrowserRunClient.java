package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.LinkScreenshot;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class BrowserRunClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(10);
    private static final String USER_AGENT = "hrva.cc-screenshots/1.0 (+https://hrva.cc/.well-known/security.txt)";
    private static final String PHONE_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
            + "Version/18.6 Mobile/15E148 Safari/604.1";
    private static final String BEARER = "Bearer ";
    private static final String SNAPSHOT_PATH = "/browser-run/snapshot";
    private static final int VIEWPORT_WIDTH = 390;
    private static final int VIEWPORT_HEIGHT = 844;
    private static final int DEVICE_SCALE_FACTOR = 2;
    private static final int JPEG_QUALITY = 70;
    private static final int PAGE_LOAD_TIMEOUT_MILLIS = 20_000;
    private static final int MAX_TITLE_LENGTH = 300;
    private static final String DAILY_LIMIT_MARKER = "Browser time limit exceeded";
    private static final String NAVIGATION_TIMEOUT_MARKER = "Navigation timeout";
    private static final String TOO_LARGE = "The screenshot was too large.";
    private static final String TIMED_OUT = "The page didn't load within 20 seconds.";
    private static final String NOT_OPENED = "Cloudflare couldn't open the page (%s).";
    private static final String NO_ANSWER = "no answer";
    private static final String JPEG = "image/jpeg";
    private static final String PNG = "image/png";
    private static final String WEBP = "image/webp";

    private final AppProperties appProperties;
    private final JsonMapper jsonMapper;
    private final Duration requestTimeout;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Autowired
    public BrowserRunClient(final AppProperties appProperties, final JsonMapper jsonMapper) {
        this(appProperties, jsonMapper, REQUEST_TIMEOUT);
    }

    BrowserRunClient(final AppProperties appProperties, final JsonMapper jsonMapper, final Duration requestTimeout) {
        this.appProperties = appProperties;
        this.jsonMapper = jsonMapper;
        this.requestTimeout = requestTimeout;
    }

    public PageSnapshot snapshot(final String url) {
        final var settings = appProperties.getCloudflare();
        if (StringUtils.isAnyBlank(settings.getAccountId(), settings.getBrowserApiToken())) {
            log.debug("Browser Run call skipped, the account or token is not set");
            return PageSnapshot.notConfigured();
        }
        final HttpRequest request;
        try {
            request = request(url, settings);
        } catch (final RuntimeException exception) {
            return failed(0, NOT_OPENED.formatted(NO_ANSWER), exception.getClass().getName());
        }
        final HttpResponse<String> response;
        try {
            response = exchange(request);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.info("Browser Run snapshot interrupted, the screenshot stays queued");
            return PageSnapshot.interrupted();
        } catch (final TimeoutException exception) {
            return failed(0, TIMED_OUT, exception.getClass().getName());
        } catch (final ExecutionException exception) {
            final var cause = exception.getCause();
            return failed(0, cause instanceof HttpTimeoutException ? TIMED_OUT : NOT_OPENED.formatted(NO_ANSWER),
                    cause == null ? "unknown" : cause.getClass().getName());
        } catch (final RuntimeException exception) {
            return failed(0, NOT_OPENED.formatted(NO_ANSWER), exception.getClass().getName());
        }
        return answer(response);
    }

    private HttpRequest request(final String url, final AppProperties.Cloudflare settings) {
        final var base = StringUtils.stripEnd(settings.getApiBase(), "/");
        final var endpoint = base + "/accounts/" + URLEncoder.encode(settings.getAccountId().strip(), StandardCharsets.UTF_8) + SNAPSHOT_PATH;
        return HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(requestTimeout)
                .header(HttpHeaders.AUTHORIZATION, BEARER + settings.getBrowserApiToken().strip())
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body(url), StandardCharsets.UTF_8))
                .build();
    }

    private String body(final String url) {
        final var body = jsonMapper.createObjectNode();
        body.put("url", url);
        body.putObject("viewport").put("width", VIEWPORT_WIDTH).put("height", VIEWPORT_HEIGHT).put("deviceScaleFactor", DEVICE_SCALE_FACTOR);
        body.put("userAgent", PHONE_USER_AGENT);
        body.putObject("screenshotOptions").put("type", "jpeg").put("quality", JPEG_QUALITY);
        body.putObject("gotoOptions").put("waitUntil", "networkidle2").put("timeout", PAGE_LOAD_TIMEOUT_MILLIS);
        return jsonMapper.writeValueAsString(body);
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

    private PageSnapshot answer(final HttpResponse<String> response) {
        final var status = response.statusCode();
        final var body = StringUtils.defaultString(response.body());
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            if (body.contains(DAILY_LIMIT_MARKER)) {
                log.warn("Browser Run call refused status={} failure=daily limit", status);
                return PageSnapshot.dailyLimit();
            }
            final var retryAfter = retryAfter(response);
            log.warn("Browser Run call refused status={} failure=rate limited retryAfterSeconds={}", status, retryAfter.toSeconds());
            return PageSnapshot.rateLimited(retryAfter);
        }
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            log.debug("Browser Run call refused status={} failure=token refused", status);
            return PageSnapshot.tokenRefused();
        }
        if (status / 100 != 2) {
            return failed(status, StringUtils.containsIgnoreCase(body, NAVIGATION_TIMEOUT_MARKER) ? TIMED_OUT : NOT_OPENED.formatted(status), null);
        }
        final var json = parse(body);
        if (json.isEmpty() || !json.get().path("success").asBoolean(false)) {
            return failed(status, NOT_OPENED.formatted(status), null);
        }
        return taken(status, json.get());
    }

    private PageSnapshot taken(final int status, final JsonNode body) {
        final var encoded = body.path("result").path("screenshot").stringValueOpt().orElse("");
        if (encoded.length() / 4L * 3 > LinkScreenshot.MAX_IMAGE_BYTES + 2L) {
            return failed(status, TOO_LARGE, null);
        }
        final byte[] image;
        try {
            image = Base64.getMimeDecoder().decode(encoded);
        } catch (final IllegalArgumentException exception) {
            return failed(status, NOT_OPENED.formatted(status), exception.getClass().getName());
        }
        if (image.length > LinkScreenshot.MAX_IMAGE_BYTES) {
            return failed(status, TOO_LARGE, null);
        }
        final var contentType = contentTypeOf(image);
        if (contentType.isEmpty()) {
            return failed(status, NOT_OPENED.formatted(status), null);
        }
        final var meta = body.path("meta");
        final var pageStatus = meta.path("status").isIntegralNumber() ? Integer.valueOf(meta.path("status").asInt()) : null;
        log.info("Browser Run screenshot taken bytes={} pageStatus={}", image.length, pageStatus);
        return PageSnapshot.taken(image, contentType.get(), title(meta, body.path("result")), pageStatus);
    }

    private static String title(final JsonNode meta, final JsonNode result) {
        final var fromMeta = StringUtils.normalizeSpace(meta.path("title").stringValueOpt().orElse(null));
        if (StringUtils.isNotBlank(fromMeta)) {
            return StringUtils.truncate(fromMeta, MAX_TITLE_LENGTH);
        }
        final var content = result.path("content").stringValueOpt().orElse(null);
        if (StringUtils.isBlank(content)) {
            return null;
        }
        try {
            final var fromHtml = StringUtils.normalizeSpace(Jsoup.parse(content).title());
            return StringUtils.isBlank(fromHtml) ? null : StringUtils.truncate(fromHtml, MAX_TITLE_LENGTH);
        } catch (final RuntimeException exception) {
            return null;
        }
    }

    private static Optional<String> contentTypeOf(final byte[] image) {
        if (image.length >= 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 && (image[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (image.length >= 8 && (image[0] & 0xFF) == 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G') {
            return Optional.of(PNG);
        }
        if (image.length >= 12 && image[0] == 'R' && image[1] == 'I' && image[2] == 'F' && image[3] == 'F'
                && image[8] == 'W' && image[9] == 'E' && image[10] == 'B' && image[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static Duration retryAfter(final HttpResponse<String> response) {
        return response.headers().firstValue(HttpHeaders.RETRY_AFTER)
                .map(String::strip)
                .filter(value -> value.matches("\\d{1,6}"))
                .map(Long::parseLong)
                .filter(seconds -> seconds > 0)
                .map(Duration::ofSeconds)
                .orElse(DEFAULT_RETRY_AFTER);
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

    private static PageSnapshot failed(final int status, final String failure, final String exception) {
        if (exception == null) {
            log.warn("Browser Run call failed status={} failure={}", status, failure);
        } else {
            log.warn("Browser Run call failed status={} failure={} exception={}", status, failure, exception);
        }
        return PageSnapshot.failed(failure);
    }

}
