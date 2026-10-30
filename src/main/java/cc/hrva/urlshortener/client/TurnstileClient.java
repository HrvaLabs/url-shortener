package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class TurnstileClient {

    private static final URI SITEVERIFY = URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_AGENT = "hrva.cc-turnstile/1.0 (+https://hrva.cc/.well-known/security.txt)";
    private static final String TIMEOUT = "timeout";
    private static final String ERROR = "error";
    private static final String INTERRUPTED = "interrupted";
    private static final String UNREADABLE = "unreadable";
    private static final String STATUS = "status ";

    private final AppProperties appProperties;
    private final JsonMapper jsonMapper;
    private final URI siteverify;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    @Autowired
    public TurnstileClient(final AppProperties appProperties, final JsonMapper jsonMapper) {
        this(appProperties, jsonMapper, SITEVERIFY, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    TurnstileClient(final AppProperties appProperties, final JsonMapper jsonMapper, final URI siteverify, final Duration connectTimeout,
            final Duration requestTimeout) {
        this.appProperties = appProperties;
        this.jsonMapper = jsonMapper;
        this.siteverify = siteverify;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public SiteverifyResult verify(final String token) {
        final var request = HttpRequest.newBuilder(siteverify)
                .timeout(requestTimeout)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(formOf(appProperties.getTurnstile().getSecretKey(), token), StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> response;
        try {
            response = exchange(request);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unreachable(INTERRUPTED, exception.getClass().getName());
        } catch (final TimeoutException exception) {
            return unreachable(TIMEOUT, exception.getClass().getName());
        } catch (final ExecutionException exception) {
            final var cause = exception.getCause();
            return unreachable(cause instanceof HttpTimeoutException ? TIMEOUT : ERROR, cause == null ? "unknown" : cause.getClass().getName());
        } catch (final RuntimeException exception) {
            return unreachable(ERROR, exception.getClass().getName());
        }
        if (response.statusCode() / 100 != 2) {
            return unreachable(STATUS + response.statusCode(), null);
        }
        return parse(response.body())
                .filter(body -> body.path("success").isBoolean())
                .<SiteverifyResult>map(body -> new SiteverifyResult.Answered(body.path("success").asBoolean(), hostnameOf(body), errorCodesOf(body)))
                .orElseGet(() -> unreachable(UNREADABLE, null));
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

    private static String formOf(final String secret, final String token) {
        return "secret=" + encode(secret) + "&response=" + encode(token);
    }

    private static String encode(final String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }

    private static String hostnameOf(final JsonNode body) {
        return body.path("hostname").stringValueOpt().orElse(null);
    }

    private static List<String> errorCodesOf(final JsonNode body) {
        final var codes = body.path("error-codes");
        if (!codes.isArray()) {
            return List.of();
        }
        return codes.valueStream()
                .map(JsonNode::stringValueOpt)
                .flatMap(Optional::stream)
                .toList();
    }

    private static SiteverifyResult unreachable(final String reason, final String exception) {
        if (exception == null) {
            log.warn("Turnstile siteverify failed reason={}", reason);
        } else {
            log.warn("Turnstile siteverify failed reason={} exception={}", reason, exception);
        }
        return new SiteverifyResult.Unreachable(reason);
    }

}
