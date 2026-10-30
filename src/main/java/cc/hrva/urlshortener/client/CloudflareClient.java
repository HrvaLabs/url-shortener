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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

@Slf4j
@Component
public class CloudflareClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "hrva.cc-domains/1.0 (+https://hrva.cc/.well-known/security.txt)";
    private static final String BEARER = "Bearer ";
    private static final String HOSTNAMES = "/custom_hostnames";
    private static final int PAGE_SIZE = 50;
    private static final int PROBE_PAGE_SIZE = 5;
    private static final String DCV_METHOD = "http";
    private static final String CERTIFICATE_TYPE = "dv";
    private static final String ACTIVE = "active";
    private static final String TIMEOUT = "timeout";
    private static final String ERROR = "error";
    private static final String REDIRECTED = "redirected";
    private static final String SERVER_ERROR = "server error";
    private static final String RATE_LIMITED = "rate limited";
    private static final String UNREADABLE = "unreadable";
    private static final String INVALID = "invalid";
    private static final String INTERRUPTED = "interrupted";
    private static final String NOT_CONFIGURED = "not configured";
    private static final String REFUSED = "refused";
    private static final String VERIFY = "verify";

    private final AppProperties appProperties;
    private final JsonMapper jsonMapper;
    private final Duration requestTimeout;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Autowired
    public CloudflareClient(final AppProperties appProperties, final JsonMapper jsonMapper) {
        this(appProperties, jsonMapper, REQUEST_TIMEOUT);
    }

    CloudflareClient(final AppProperties appProperties, final JsonMapper jsonMapper, final Duration requestTimeout) {
        this.appProperties = appProperties;
        this.jsonMapper = jsonMapper;
        this.requestTimeout = requestTimeout;
    }

    public CloudflareResult<Boolean> verifyToken() {
        return call(new Call<>(VERIFY, false, HttpMethod.GET, "/user/tokens/verify", null, null,
                body -> new CloudflareResult.Ok<>(ACTIVE.equals(text(body.path("result"), "status")), null)));
    }

    public CloudflareResult<List<CloudflareHostname>> list(final int page) {
        return call(new Call<>("list", true, HttpMethod.GET, HOSTNAMES + "?per_page=" + PAGE_SIZE + "&page=" + page, null, null,
                body -> hostnames(body).filter(found -> total(body) != null)
                        .<CloudflareResult<List<CloudflareHostname>>>map(found -> new CloudflareResult.Ok<>(found, total(body)))
                        .orElseGet(() -> new CloudflareResult.Unreachable<>(UNREADABLE))));
    }

    public CloudflareResult<Integer> totalCount() {
        return call(new Call<>("total", true, HttpMethod.GET, HOSTNAMES + "?per_page=" + PROBE_PAGE_SIZE, null, null,
                body -> Optional.ofNullable(total(body)).<CloudflareResult<Integer>>map(total -> new CloudflareResult.Ok<>(total, total))
                        .orElseGet(() -> new CloudflareResult.Unreachable<>(UNREADABLE))));
    }

    public CloudflareResult<Optional<CloudflareHostname>> findByHostname(final String hostname) {
        return call(new Call<>("find", true, HttpMethod.GET, HOSTNAMES + "?hostname=" + encode(hostname) + "&per_page=" + PAGE_SIZE, null, null,
                body -> hostnames(body).<CloudflareResult<Optional<CloudflareHostname>>>map(found -> new CloudflareResult.Ok<>(found.stream()
                                .filter(candidate -> Strings.CI.equals(candidate.hostname(), hostname))
                                .findFirst(), total(body)))
                        .orElseGet(() -> new CloudflareResult.Unreachable<>(UNREADABLE))));
    }

    public CloudflareResult<CloudflareHostname> get(final String id) {
        return call(new Call<>("get", true, HttpMethod.GET, HOSTNAMES + "/" + encode(id), null, new CloudflareResult.NotFound<>(),
                this::singleHostname));
    }

    public CloudflareResult<CloudflareHostname> create(final String hostname) {
        final var request = jsonMapper.createObjectNode();
        request.put("hostname", hostname);
        request.putObject("ssl").put("method", DCV_METHOD).put("type", CERTIFICATE_TYPE);
        return call(new Call<>("create", true, HttpMethod.POST, HOSTNAMES, jsonMapper.writeValueAsString(request), null,
                this::singleHostname));
    }

    public CloudflareResult<Void> delete(final String id) {
        return call(new Call<>("delete", true, HttpMethod.DELETE, HOSTNAMES + "/" + encode(id), null, new CloudflareResult.Ok<>(null, null),
                body -> new CloudflareResult.Ok<>(null, null), false));
    }

    public CloudflareResult<Quota> quota() {
        return call(new Call<>("quota", true, HttpMethod.GET, HOSTNAMES + "/quota", null, null, body -> {
            final var result = body.path("result");
            if (!result.path("used").isIntegralNumber() || !result.path("allocated").isIntegralNumber()) {
                return new CloudflareResult.Unreachable<>(UNREADABLE);
            }
            return new CloudflareResult.Ok<>(new Quota(result.path("used").asInt(), result.path("allocated").asInt()), null);
        }));
    }

    private <T> CloudflareResult<T> call(final Call<T> call) {
        final var settings = appProperties.getCloudflare();
        if (StringUtils.isBlank(settings.getApiToken()) || call.zoneScoped() && StringUtils.isBlank(settings.getZoneId())) {
            log.debug("Cloudflare call skipped, the token or zone is not set call={}", call.name());
            return new CloudflareResult.Unreachable<>(NOT_CONFIGURED);
        }
        final HttpRequest request;
        try {
            request = request(call, settings);
        } catch (final RuntimeException exception) {
            return unreachable(call, 0, INVALID, exception.getClass().getName());
        }
        final HttpResponse<String> response;
        try {
            response = exchange(request);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unreachable(call, 0, INTERRUPTED, exception.getClass().getName());
        } catch (final TimeoutException exception) {
            return unreachable(call, 0, TIMEOUT, exception.getClass().getName());
        } catch (final ExecutionException exception) {
            final var cause = exception.getCause();
            return unreachable(call, 0, cause instanceof HttpTimeoutException ? TIMEOUT : ERROR, cause == null ? "unknown" : cause.getClass().getName());
        } catch (final RuntimeException exception) {
            return unreachable(call, 0, ERROR, exception.getClass().getName());
        }
        return answer(call, response.statusCode(), response.body());
    }

    private HttpRequest request(final Call<?> call, final AppProperties.Cloudflare settings) {
        final var base = StringUtils.stripEnd(settings.getApiBase(), "/");
        final var zone = call.zoneScoped() ? "/zones/" + encode(settings.getZoneId()) : "";
        final var builder = HttpRequest.newBuilder(URI.create(base + zone + call.path()))
                .timeout(requestTimeout)
                .header(HttpHeaders.AUTHORIZATION, BEARER + settings.getApiToken())
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (call.body() == null) {
            return builder.method(call.method().name(), HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .method(call.method().name(), HttpRequest.BodyPublishers.ofString(call.body(), StandardCharsets.UTF_8))
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

    private <T> CloudflareResult<T> answer(final Call<T> call, final int status, final String body) {
        if (status / 100 == 3) {
            return unreachable(call, status, REDIRECTED, null);
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return unreachable(call, status, RATE_LIMITED, null);
        }
        if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return unreachable(call, status, SERVER_ERROR, null);
        }
        if (status == HttpStatus.NOT_FOUND.value() && call.onNotFound() != null) {
            log.debug("Cloudflare has no such object call={} status={}", call.name(), status);
            return call.onNotFound();
        }
        final var json = parse(body);
        if (status / 100 == 2) {
            if (json.isEmpty() && !call.readsBody() && StringUtils.isBlank(body)) {
                return call.onSuccess().apply(MissingNode.getInstance());
            }
            if (json.isEmpty()) {
                return unreachable(call, status, UNREADABLE, null);
            }
            if (!json.get().path("success").asBoolean(true)) {
                return refused(call, status, json.get());
            }
            try {
                final var result = call.onSuccess().apply(json.get());
                if (result instanceof CloudflareResult.Unreachable<T>) {
                    return unreachable(call, status, UNREADABLE, null);
                }
                return result;
            } catch (final RuntimeException exception) {
                return unreachable(call, status, UNREADABLE, exception.getClass().getName());
            }
        }
        return json.<CloudflareResult<T>>map(node -> refused(call, status, node)).orElseGet(() -> refused(call, status, null));
    }

    private <T> CloudflareResult<T> refused(final Call<T> call, final int status, final JsonNode body) {
        final var error = body == null ? null : body.path("errors").path(0);
        final var code = error != null && error.path("code").isIntegralNumber() ? error.path("code").asInt() : 0;
        final var message = error == null ? null : text(error, "message");
        if (VERIFY.equals(call.name())) {
            log.debug("Cloudflare call failed call={} status={} code={} failure={}", call.name(), status, code, REFUSED);
        } else {
            log.warn("Cloudflare call failed call={} status={} code={} failure={}", call.name(), status, code, REFUSED);
        }
        return new CloudflareResult.Refused<>(status, code, message);
    }

    private static <T> CloudflareResult<T> unreachable(final Call<T> call, final int status, final String failure, final String exception) {
        if (exception == null) {
            log.warn("Cloudflare call failed call={} status={} code={} failure={}", call.name(), status, 0, failure);
        } else {
            log.warn("Cloudflare call failed call={} status={} code={} failure={} exception={}", call.name(), status, 0, failure, exception);
        }
        return new CloudflareResult.Unreachable<>(failure);
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

    private CloudflareResult<CloudflareHostname> singleHostname(final JsonNode body) {
        final var result = body.path("result");
        if (!result.isObject() || text(result, "id") == null) {
            return new CloudflareResult.Unreachable<>(UNREADABLE);
        }
        return new CloudflareResult.Ok<>(hostname(result), null);
    }

    private static Optional<List<CloudflareHostname>> hostnames(final JsonNode body) {
        final var result = body.path("result");
        if (!result.isArray()) {
            return Optional.empty();
        }
        final var hostnames = new ArrayList<CloudflareHostname>();
        for (final var node : result) {
            if (node.isObject() && text(node, "id") != null) {
                hostnames.add(hostname(node));
            }
        }
        return Optional.of(List.copyOf(hostnames));
    }

    private static CloudflareHostname hostname(final JsonNode node) {
        final var ssl = node.path("ssl");
        final var verificationErrors = new ArrayList<String>();
        for (final var error : node.path("verification_errors")) {
            error.stringValueOpt().ifPresent(verificationErrors::add);
        }
        final var validationErrors = new ArrayList<String>();
        for (final var error : ssl.path("validation_errors")) {
            error.path("message").stringValueOpt().ifPresent(validationErrors::add);
        }
        return new CloudflareHostname(text(node, "id"), text(node, "hostname"), text(node, "status"), text(ssl, "status"),
                verificationErrors, validationErrors);
    }

    private static Integer total(final JsonNode body) {
        final var total = body.path("result_info").path("total_count");
        return total.isIntegralNumber() ? total.asInt() : null;
    }

    private static String text(final JsonNode node, final String field) {
        return node.path(field).stringValueOpt().orElse(null);
    }

    private static String encode(final String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record Quota(int used, int allocated) {
    }

    private record Call<T>(String name, boolean zoneScoped, HttpMethod method, String path, String body, CloudflareResult<T> onNotFound,
                           Function<JsonNode, CloudflareResult<T>> onSuccess, boolean readsBody) {

        Call(final String name, final boolean zoneScoped, final HttpMethod method, final String path, final String body,
             final CloudflareResult<T> onNotFound, final Function<JsonNode, CloudflareResult<T>> onSuccess) {
            this(name, zoneScoped, method, path, body, onNotFound, onSuccess, true);
        }

    }

}
