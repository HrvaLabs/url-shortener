package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.client.PinnedHttpTransport.RefusedAddressException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.util.PublicAddresses;
import cc.hrva.urlshortener.util.RedirectLocations;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

public class PublicHttpFetcher {

    public static final String LINK_CHECKER_USER_AGENT = "hrva.cc-linkcheck/1.0 (+https://app.hrva.cc/help/notifications#link-checker)";
    public static final String POST_INVALID = "invalid";

    private static final Logger log = LoggerFactory.getLogger(PublicHttpFetcher.class);
    private static final int MAX_REDIRECTS = 3;
    private static final long MEGABYTE = 1024L * 1024L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final String USER_AGENT = "hrva.cc-linkcheck/1.0 (+https://hrva.cc/.well-known/security.txt)";
    private static final String TEMP_FILE_PREFIX = "hrva-fetch-";
    private static final Path TEMP_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"));
    private static final String NOT_ALLOWED = "not a public https address";
    private static final String NOT_PUBLIC = "not a public address";
    private static final String TIMED_OUT = "timed out";
    private static final String NOTIFY_USER_AGENT = "hrva.cc-notify/1.0 (+https://app.hrva.cc/help/notifications)";
    private static final int MAX_PROBE_REDIRECTS = 5;
    private static final Set<Integer> CONFIRMED_WITH_GET = Set.of(404, 405, 410, 501);
    private static final String PROBE_RANGE = "bytes=0-1023";
    private static final String PROBE_ERROR = "error";
    private static final String PROBE_UNREACHABLE = "unreachable";
    private static final String POST_NOT_ALLOWED = "not-allowed";
    private static final String POST_REDIRECTED = "redirected";
    private static final String POST_TIMEOUT = "timeout";
    private static final String POST_ERROR = "error";

    private final Predicate<String> targetPolicy;
    private final Path downloadDirectory;
    private final PinnedHttpTransport transport;

    public PublicHttpFetcher(final Predicate<String> targetPolicy) {
        this(targetPolicy, TEMP_DIRECTORY);
    }

    public PublicHttpFetcher(final Predicate<String> targetPolicy, final Path downloadDirectory) {
        this(targetPolicy, downloadDirectory, Resolver.SYSTEM, PublicAddresses::isPublicAddress, SSLContexts.createSystemDefault());
    }

    public PublicHttpFetcher(final Predicate<String> targetPolicy, final Predicate<InetAddress> addressPolicy) {
        this(targetPolicy, TEMP_DIRECTORY, Resolver.SYSTEM, addressPolicy, SSLContexts.createSystemDefault());
    }

    PublicHttpFetcher(final Predicate<String> targetPolicy, final Path downloadDirectory, final Predicate<InetAddress> addressPolicy) {
        this(targetPolicy, downloadDirectory, Resolver.SYSTEM, addressPolicy, SSLContexts.createSystemDefault());
    }

    PublicHttpFetcher(final Predicate<String> targetPolicy, final Resolver resolver, final Predicate<InetAddress> addressPolicy) {
        this(targetPolicy, resolver, addressPolicy, SSLContexts.createSystemDefault());
    }

    PublicHttpFetcher(final Predicate<String> targetPolicy, final Resolver resolver, final Predicate<InetAddress> addressPolicy,
            final SSLContext sslContext) {
        this(targetPolicy, TEMP_DIRECTORY, resolver, addressPolicy, sslContext);
    }

    private PublicHttpFetcher(final Predicate<String> targetPolicy, final Path downloadDirectory, final Resolver resolver,
            final Predicate<InetAddress> addressPolicy, final SSLContext sslContext) {
        this.targetPolicy = targetPolicy;
        this.downloadDirectory = downloadDirectory;
        this.transport = new PinnedHttpTransport(resolver, addressPolicy, sslContext, CONNECT_TIMEOUT);
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
        return fetch(url, maxBytes, validators(etag, lastModified), requestTimeout);
    }

    public FetchResult fetch(final String url, final long maxBytes, final Map<String, String> headers, final Duration requestTimeout) {
        return fetch(url, maxBytes, headers, requestTimeout, false);
    }

    public FetchResult fetchFirst(final String url, final long maxBytes, final Map<String, String> headers, final Duration requestTimeout) {
        return fetch(url, maxBytes, headers, requestTimeout, true);
    }

    private FetchResult fetch(final String url, final long maxBytes, final Map<String, String> headers, final Duration requestTimeout,
            final boolean firstBytesOnly) {
        final var deadline = System.nanoTime() + requestTimeout.toNanos();
        var target = url;
        for (int redirects = 0; ; redirects++) {
            if (!targetPolicy.test(target)) {
                throw new UrlValidationException(NOT_ALLOWED);
            }

            if (deadline - System.nanoTime() <= 0) {
                throw new UrlValidationException(TIMED_OUT);
            }
            final var response = send(target, headers, deadline);
            final var location = response.header(HttpHeaders.LOCATION);
            if (!REDIRECT_STATUSES.contains(response.status()) || location.isEmpty()) {
                return result(response, maxBytes, deadline, firstBytesOnly);
            }

            response.close();
            if (redirects == MAX_REDIRECTS) {
                throw new UrlValidationException("too many redirects");
            }
            target = resolve(target, location.get());
        }
    }

    public ProbeResult probe(final String url, final Duration budget, final Consumer<String> beforeEachRequest) {
        try {
            return probeHops(url, new ProbeDeadline(System.nanoTime() + budget.toNanos()), beforeEachRequest);
        } catch (final IOException exception) {
            return classify(exception);
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
            final var sent = new LinkedHashMap<String, String>();
            sent.put(HttpHeaders.CONTENT_TYPE, contentType);
            sent.put(HttpHeaders.USER_AGENT, NOTIFY_USER_AGENT);
            sent.putAll(headers);
            final var response = transport.post(URI.create(url), sent, body, System.nanoTime() + timeout.toNanos());
            response.close();
            final var status = response.status();
            return new PostResult(status, status / 100 == 3 ? POST_REDIRECTED : null);
        } catch (final RefusedAddressException exception) {
            log.debug("Channel post refused a non-public address");
            return new PostResult(0, POST_NOT_ALLOWED);
        } catch (final IllegalArgumentException exception) {
            log.debug("Channel post refused before sending exception={}", exception.getClass().getName());
            return new PostResult(0, POST_INVALID);
        } catch (final IOException exception) {
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
            var answer = probeRequest(uri.get(), false, deadline, beforeEachRequest);
            if (answer.refusal() == null && CONFIRMED_WITH_GET.contains(answer.status())) {
                answer = probeRequest(uri.get(), true, deadline, beforeEachRequest);
            }
            if (answer.refusal() != null) {
                return answer.refusal();
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

    private ProbeAnswer probeRequest(final URI uri, final boolean get, final ProbeDeadline deadline,
            final Consumer<String> beforeEachRequest) throws IOException, InterruptedException {
        final var waitStarted = System.nanoTime();
        beforeEachRequest.accept(uri.getHost());
        deadline.extend(System.nanoTime() - waitStarted);
        final PinnedHttpTransport.Response response;
        try {
            response = get
                    ? transport.get(uri, Map.of(HttpHeaders.USER_AGENT, LINK_CHECKER_USER_AGENT, HttpHeaders.RANGE, PROBE_RANGE), deadline.expiresAt())
                    : transport.head(uri, Map.of(HttpHeaders.USER_AGENT, LINK_CHECKER_USER_AGENT), deadline.expiresAt());
        } catch (final UnknownHostException exception) {
            return new ProbeAnswer(0, null, new ProbeResult(ProbeOutcome.DEAD, "nxdomain", null));
        } catch (final RefusedAddressException exception) {
            return new ProbeAnswer(0, null, new ProbeResult(ProbeOutcome.UNKNOWN, "private", null));
        }
        response.close();
        return new ProbeAnswer(response.status(), response.header(HttpHeaders.LOCATION).orElse(null), null);
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

    static ProbeResult classify(final Throwable exception) {
        if (ExceptionUtils.indexOfType(exception, HttpTimeoutException.class) >= 0) {
            return new ProbeResult(ProbeOutcome.UNKNOWN, "timeout", null);
        }
        final var chain = ExceptionUtils.getThrowableList(exception);
        final var rootCause = chain.getLast();
        if (rootCause instanceof ConnectException || refusedThenClosed(chain)) {
            return new ProbeResult(ProbeOutcome.DEAD, "refused", null);
        }
        if (rootCause instanceof SocketException || rootCause instanceof EOFException || rootCause instanceof UnresolvedAddressException
                || rootCause instanceof NoHttpResponseException) {
            return new ProbeResult(ProbeOutcome.UNKNOWN, PROBE_UNREACHABLE, null);
        }
        if (ExceptionUtils.indexOfType(exception, SSLException.class) >= 0) {
            return new ProbeResult(ProbeOutcome.DEAD, "tls", null);
        }
        return new ProbeResult(ProbeOutcome.UNKNOWN, PROBE_ERROR, null);
    }

    private static boolean refusedThenClosed(final List<Throwable> chain) {
        return chain.size() > 1 && chain.getLast().getClass() == ClosedChannelException.class && chain.get(chain.size() - 2) instanceof ConnectException;
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
        if (deadline - System.nanoTime() <= 0) {
            throw new UrlValidationException(TIMED_OUT);
        }
        final var response = send(url, Map.of(), deadline);
        response.close();
        final var location = response.header(HttpHeaders.LOCATION);
        if (!REDIRECT_STATUSES.contains(response.status()) || location.isEmpty()) {
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

    private static Map<String, String> validators(final String etag, final String lastModified) {
        final var headers = new LinkedHashMap<String, String>();
        if (StringUtils.isNotBlank(etag)) {
            headers.put(HttpHeaders.IF_NONE_MATCH, etag);
        }
        if (StringUtils.isNotBlank(lastModified)) {
            headers.put(HttpHeaders.IF_MODIFIED_SINCE, lastModified);
        }
        return headers;
    }

    private PinnedHttpTransport.Response send(final String url, final Map<String, String> headers, final long deadline) {
        try {
            final var sent = new LinkedHashMap<>(headers);
            sent.putIfAbsent(HttpHeaders.USER_AGENT, USER_AGENT);
            return transport.get(URI.create(url), sent, deadline);
        } catch (final IllegalArgumentException exception) {
            throw new UrlValidationException("not a valid address", exception);
        } catch (final RefusedAddressException exception) {
            throw new UrlValidationException(NOT_PUBLIC, exception);
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

    private FetchResult result(final PinnedHttpTransport.Response response, final long maxBytes, final long deadline,
            final boolean firstBytesOnly) {
        final var etag = response.header(HttpHeaders.ETAG).orElse(null);
        final var lastModified = response.header(HttpHeaders.LAST_MODIFIED).orElse(null);
        if (response.status() == HttpStatus.NOT_MODIFIED.value()) {
            response.close();
            return new FetchResult(true, null, etag, lastModified);
        }
        if (response.status() / 100 != 2) {
            response.close();
            throw new UrlValidationException("HTTP " + response.status());
        }
        try {
            if (!firstBytesOnly && response.longHeader(HttpHeaders.CONTENT_LENGTH).orElse(0) > maxBytes) {
                throw tooLarge(maxBytes);
            }
            return new FetchResult(false, download(response.body(), maxBytes, deadline, firstBytesOnly), etag, lastModified);
        } catch (final RuntimeException exception) {
            response.close();
            throw exception;
        }
    }

    private Path download(final InputStream body, final long maxBytes, final long deadline, final boolean firstBytesOnly) {
        final var file = createTempFile();
        final var timedOut = new AtomicBoolean();
        final var watchdog = CompletableFuture.runAsync(() -> abort(body, timedOut),
                CompletableFuture.delayedExecutor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        try (body;
                final var bounded = BoundedInputStream.builder().setInputStream(body).setMaxCount(firstBytesOnly ? maxBytes : maxBytes + 1).get();
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
            throw new UrlValidationException(timedOut.get() || exception instanceof SocketTimeoutException ? TIMED_OUT : "download failed", exception);
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

    private record ProbeAnswer(int status, String location, ProbeResult refusal) {
    }

    private static final class ProbeDeadline {

        private long deadline;

        private ProbeDeadline(final long deadline) {
            this.deadline = deadline;
        }

        private void extend(final long nanos) {
            deadline += nanos;
        }

        private long expiresAt() {
            return deadline;
        }

    }

}
