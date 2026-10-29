package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.util.PublicAddresses;
import cc.hrva.urlshortener.util.RedirectLocations;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.net.ssl.SSLException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

public class PublicHttpFetcher {

    public static final String LINK_CHECKER_USER_AGENT = "hrva.cc-linkcheck/1.0 (+https://app.hrva.cc/help/notifications#link-checker)";

    private static final Logger log = LoggerFactory.getLogger(PublicHttpFetcher.class);
    private static final int MAX_REDIRECTS = 3;
    private static final long MEGABYTE = 1024L * 1024L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final String USER_AGENT = "hrva.cc-linkcheck/1.0 (+https://hrva.cc/.well-known/security.txt)";
    private static final String TEMP_FILE_PREFIX = "hrva-fetch-";
    private static final String NOT_ALLOWED = "not a public https address";
    private static final String NOT_PUBLIC = "not a public address";
    private static final String TIMED_OUT = "timed out";
    private static final String NOTIFY_USER_AGENT = "hrva.cc-notify/1.0 (+https://app.hrva.cc/help/notifications)";
    private static final int MAX_PROBE_REDIRECTS = 5;
    private static final Set<Integer> CONFIRMED_WITH_GET = Set.of(404, 405, 410, 501);
    private static final String PROBE_RANGE = "bytes=0-1023";
    private static final String PROBE_ERROR = "error";
    private static final String POST_NOT_ALLOWED = "not-allowed";
    private static final String POST_REDIRECTED = "redirected";
    private static final String POST_TIMEOUT = "timeout";
    private static final String POST_ERROR = "error";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Predicate<String> targetPolicy;
    private final Path downloadDirectory;
    private final Resolver resolver;

    public PublicHttpFetcher(final Predicate<String> targetPolicy) {
        this(targetPolicy, Path.of(System.getProperty("java.io.tmpdir")));
    }

    public PublicHttpFetcher(final Predicate<String> targetPolicy, final Path downloadDirectory) {
        this(targetPolicy, downloadDirectory, Resolver.SYSTEM);
    }

    PublicHttpFetcher(final Predicate<String> targetPolicy, final Resolver resolver) {
        this(targetPolicy, Path.of(System.getProperty("java.io.tmpdir")), resolver);
    }

    private PublicHttpFetcher(final Predicate<String> targetPolicy, final Path downloadDirectory, final Resolver resolver) {
        this.targetPolicy = targetPolicy;
        this.downloadDirectory = downloadDirectory;
        this.resolver = resolver;
    }

    public static Predicate<String> httpsPublicOnly() {
        return url -> isHttps(url) && PublicAddresses.isPublicTarget(url);
    }

    public static Predicate<String> httpOrHttpsPublicOnly() {
        return PublicAddresses::isPublicTarget;
    }

    public List<RedirectHop> followRedirects(final String url, final int maxHops, final Duration budget) {
        final var destination = new RedirectHop(url, normalizedDestination(url));
        if (!targetPolicy.test(destination.normalized())) {
            throw new UrlValidationException(NOT_PUBLIC);
        }

        final var deadline = System.nanoTime() + budget.toNanos();
        final var chain = new ArrayList<RedirectHop>();
        chain.add(destination);
        while (chain.size() <= maxHops) {
            final var next = nextHop(chain, deadline);
            if (next.isEmpty()) {
                break;
            }
            chain.add(next.get());
            if (!targetPolicy.test(next.get().normalized())) {
                break;
            }
        }
        return List.copyOf(chain);
    }

    public FetchResult fetch(final String url, final long maxBytes, final String etag, final String lastModified,
            final Duration requestTimeout) {
        final var deadline = System.nanoTime() + requestTimeout.toNanos();
        var target = url;
        for (int redirects = 0; ; redirects++) {
            if (!targetPolicy.test(target)) {
                throw new UrlValidationException(NOT_ALLOWED);
            }

            final var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new UrlValidationException(TIMED_OUT);
            }
            final var response = send(request(target, etag, lastModified, Duration.ofNanos(remaining)));
            final var location = response.headers().firstValue(HttpHeaders.LOCATION);
            if (!REDIRECT_STATUSES.contains(response.statusCode()) || location.isEmpty()) {
                return result(response, maxBytes, deadline);
            }

            IOUtils.closeQuietly(response.body());
            if (redirects == MAX_REDIRECTS) {
                throw new UrlValidationException("too many redirects");
            }
            target = resolve(target, location.get());
        }
    }

    public ProbeResult probe(final String url, final Duration budget) {
        return probe(url, budget, host -> {});
    }

    public ProbeResult probe(final String url, final Duration budget, final Consumer<String> beforeEachRequest) {
        try {
            return probeHops(url, new ProbeDeadline(System.nanoTime() + budget.toNanos()), beforeEachRequest);
        } catch (final IOException exception) {
            return transportFailure(exception);
        } catch (final IllegalArgumentException exception) {
            return new ProbeResult(ProbeOutcome.UNKNOWN, PROBE_ERROR, null);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ProbeResult(ProbeOutcome.UNKNOWN, PROBE_ERROR, null);
        }
    }

    public PostResult post(final String url, final String contentType, final Map<String, String> headers, final byte[] body,
            final Duration timeout) {
        if (!targetPolicy.test(url)) {
            return new PostResult(0, POST_NOT_ALLOWED);
        }
        try {
            final var builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.USER_AGENT, NOTIFY_USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            final var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            IOUtils.closeQuietly(response.body());
            final var status = response.statusCode();
            return new PostResult(status, status / 100 == 3 ? POST_REDIRECTED : null);
        } catch (final IOException | IllegalArgumentException exception) {
            log.debug("Channel post failed exception={}", exception.getClass().getName());
            final var timedOut = ExceptionUtils.indexOfType(exception, HttpTimeoutException.class) >= 0;
            return new PostResult(0, timedOut ? POST_TIMEOUT : POST_ERROR);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new PostResult(0, POST_ERROR);
        }
    }

    private ProbeResult probeHops(final String url, final ProbeDeadline deadline, final Consumer<String> beforeEachRequest)
            throws IOException, InterruptedException {
        var target = url;
        for (int redirects = 0; ; redirects++) {
            final var uri = webAddress(target);
            if (uri.isEmpty()) {
                return new ProbeResult(ProbeOutcome.UNKNOWN, PROBE_ERROR, null);
            }
            final var refusal = refusal(uri.get().getHost());
            if (refusal.isPresent()) {
                return refusal.get();
            }

            var answer = probeRequest(uri.get(), false, deadline, beforeEachRequest);
            if (CONFIRMED_WITH_GET.contains(answer.status())) {
                answer = probeRequest(uri.get(), true, deadline, beforeEachRequest);
            }
            if (!REDIRECT_STATUSES.contains(answer.status()) || answer.location() == null) {
                return judged(answer.status());
            }
            if (redirects == MAX_PROBE_REDIRECTS) {
                return new ProbeResult(ProbeOutcome.UNKNOWN, "redirects", answer.status());
            }
            final var received = readAsUtf8(answer.location());
            target = uri.get().resolve(URI.create(RedirectLocations.normalized(received))).toString();
        }
    }

    private Optional<ProbeResult> refusal(final String host) {
        final var bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        try {
            return PublicAddresses.arePublic(resolver.resolve(bare))
                    ? Optional.empty()
                    : Optional.of(new ProbeResult(ProbeOutcome.UNKNOWN, "private", null));
        } catch (final UnknownHostException exception) {
            return Optional.of(new ProbeResult(ProbeOutcome.DEAD, "nxdomain", null));
        }
    }

    private ProbeAnswer probeRequest(final URI uri, final boolean get, final ProbeDeadline deadline,
            final Consumer<String> beforeEachRequest) throws IOException, InterruptedException {
        final var waitStarted = System.nanoTime();
        beforeEachRequest.accept(uri.getHost());
        deadline.extend(System.nanoTime() - waitStarted);
        final var remaining = deadline.remaining();
        if (remaining <= 0) {
            throw new HttpTimeoutException(TIMED_OUT);
        }
        final var builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofNanos(remaining))
                .header(HttpHeaders.USER_AGENT, LINK_CHECKER_USER_AGENT);
        if (get) {
            builder.header(HttpHeaders.RANGE, PROBE_RANGE).GET();
        } else {
            builder.method(HttpMethod.HEAD.name(), HttpRequest.BodyPublishers.noBody());
        }
        final var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        IOUtils.closeQuietly(response.body());
        return new ProbeAnswer(response.statusCode(), response.headers().firstValue(HttpHeaders.LOCATION).orElse(null));
    }

    private static Optional<URI> webAddress(final String url) {
        try {
            final var uri = URI.create(RedirectLocations.normalized(url));
            final var web = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            return web && StringUtils.isNotEmpty(uri.getHost()) ? Optional.of(uri) : Optional.empty();
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static ProbeResult judged(final int status) {
        if (status / 100 == 2 || status == HttpStatus.NOT_MODIFIED.value()) {
            return new ProbeResult(ProbeOutcome.ALIVE, "ok", status);
        }
        if (status == HttpStatus.NOT_FOUND.value() || status == HttpStatus.GONE.value()) {
            return new ProbeResult(ProbeOutcome.DEAD, String.valueOf(status), status);
        }
        return new ProbeResult(ProbeOutcome.UNKNOWN, "http-" + status, status);
    }

    private static ProbeResult transportFailure(final IOException exception) {
        if (ExceptionUtils.indexOfType(exception, HttpTimeoutException.class) >= 0) {
            return new ProbeResult(ProbeOutcome.UNKNOWN, "timeout", null);
        }
        if (ExceptionUtils.indexOfType(exception, ConnectException.class) >= 0) {
            return new ProbeResult(ProbeOutcome.DEAD, "refused", null);
        }
        if (ExceptionUtils.indexOfType(exception, SSLException.class) >= 0) {
            return new ProbeResult(ProbeOutcome.DEAD, "tls", null);
        }
        return new ProbeResult(ProbeOutcome.UNKNOWN, PROBE_ERROR, null);
    }

    private Optional<RedirectHop> nextHop(final List<RedirectHop> chain, final long deadline) {
        try {
            return redirectTarget(chain.getLast().normalized(), deadline);
        } catch (final UrlValidationException exception) {
            if (chain.size() == 1) {
                throw exception;
            }
            log.debug("Redirect chain ends at hop {} reason={}", chain.size() - 1, exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<RedirectHop> redirectTarget(final String url, final long deadline) {
        final var remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new UrlValidationException(TIMED_OUT);
        }
        final var response = send(request(url, null, null, Duration.ofNanos(remaining)));
        IOUtils.closeQuietly(response.body());
        final var location = response.headers().firstValue(HttpHeaders.LOCATION);
        if (!REDIRECT_STATUSES.contains(response.statusCode()) || location.isEmpty()) {
            return Optional.empty();
        }
        final var received = readAsUtf8(location.get());
        try {
            final var normalizedLocation = URI.create(RedirectLocations.normalized(received));
            final var next = URI.create(url).resolve(normalizedLocation).toString();
            return Optional.of(new RedirectHop(normalizedLocation.isAbsolute() ? received : next, next));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String normalizedDestination(final String url) {
        try {
            return RedirectLocations.normalized(url);
        } catch (final IllegalArgumentException exception) {
            throw new UrlValidationException(NOT_PUBLIC, exception);
        }
    }

    private static String readAsUtf8(final String headerValue) {
        return new String(headerValue.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private static boolean isHttps(final String url) {
        try {
            return "https".equalsIgnoreCase(URI.create(url).getScheme());
        } catch (final IllegalArgumentException exception) {
            return false;
        }
    }

    private static HttpRequest request(final String url, final String etag, final String lastModified,
            final Duration timeout) {
        try {
            final var builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .GET();
            if (StringUtils.isNotBlank(etag)) {
                builder.header(HttpHeaders.IF_NONE_MATCH, etag);
            }
            if (StringUtils.isNotBlank(lastModified)) {
                builder.header(HttpHeaders.IF_MODIFIED_SINCE, lastModified);
            }
            return builder.build();
        } catch (final IllegalArgumentException exception) {
            throw new UrlValidationException("not a valid address", exception);
        }
    }

    private HttpResponse<InputStream> send(final HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (final HttpTimeoutException exception) {
            throw new UrlValidationException(TIMED_OUT, exception);
        } catch (final IOException exception) {
            throw new UrlValidationException("connection failed", exception);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UrlValidationException("interrupted", exception);
        }
    }

    private static String resolve(final String url, final String location) {
        try {
            return URI.create(url).resolve(location).toString();
        } catch (final IllegalArgumentException exception) {
            throw new UrlValidationException("redirect to an invalid address", exception);
        }
    }

    private FetchResult result(final HttpResponse<InputStream> response, final long maxBytes, final long deadline) {
        final var headers = response.headers();
        final var etag = headers.firstValue(HttpHeaders.ETAG).orElse(null);
        final var lastModified = headers.firstValue(HttpHeaders.LAST_MODIFIED).orElse(null);
        if (response.statusCode() == HttpStatus.NOT_MODIFIED.value()) {
            IOUtils.closeQuietly(response.body());
            return new FetchResult(true, null, etag, lastModified);
        }
        if (response.statusCode() / 100 != 2) {
            IOUtils.closeQuietly(response.body());
            throw new UrlValidationException("HTTP " + response.statusCode());
        }
        try {
            if (headers.firstValueAsLong(HttpHeaders.CONTENT_LENGTH).orElse(0) > maxBytes) {
                throw tooLarge(maxBytes);
            }
            return new FetchResult(false, download(response.body(), maxBytes, deadline), etag, lastModified);
        } catch (final RuntimeException exception) {
            IOUtils.closeQuietly(response.body());
            throw exception;
        }
    }

    private Path download(final InputStream body, final long maxBytes, final long deadline) {
        final var file = createTempFile();
        final var timedOut = new AtomicBoolean();
        final var watchdog = CompletableFuture.runAsync(() -> abort(body, timedOut),
                CompletableFuture.delayedExecutor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        try (body;
                final var bounded = BoundedInputStream.builder().setInputStream(body).setMaxCount(maxBytes + 1).get();
                final var output = Files.newOutputStream(file)) {
            final var size = bounded.transferTo(output);
            if (timedOut.get()) {
                throw new UrlValidationException(TIMED_OUT);
            }
            if (size > maxBytes) {
                throw tooLarge(maxBytes);
            }
            return file;
        } catch (final IOException exception) {
            delete(file);
            throw new UrlValidationException(timedOut.get() ? TIMED_OUT : "download failed", exception);
        } catch (final RuntimeException exception) {
            delete(file);
            throw exception;
        } finally {
            watchdog.cancel(false);
        }
    }

    private static void abort(final InputStream body, final AtomicBoolean timedOut) {
        timedOut.set(true);
        IOUtils.closeQuietly(body);
    }

    private Path createTempFile() {
        try {
            return Files.createTempFile(downloadDirectory, TEMP_FILE_PREFIX, ".tmp");
        } catch (final IOException exception) {
            throw new UrlValidationException("could not store the download", exception);
        }
    }

    private static void delete(final Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (final IOException exception) {
            log.warn("Could not delete the temporary download {}", file, exception);
        }
    }

    private static UrlValidationException tooLarge(final long maxBytes) {
        final var limit = maxBytes >= MEGABYTE && maxBytes % MEGABYTE == 0 ? maxBytes / MEGABYTE + " MB" : maxBytes + " bytes";
        return new UrlValidationException("larger than " + limit);
    }

    private record ProbeAnswer(int status, String location) {
    }

    private static final class ProbeDeadline {

        private long deadline;

        private ProbeDeadline(final long deadline) {
            this.deadline = deadline;
        }

        private void extend(final long nanos) {
            deadline += nanos;
        }

        private long remaining() {
            return deadline - System.nanoTime();
        }

    }

}
