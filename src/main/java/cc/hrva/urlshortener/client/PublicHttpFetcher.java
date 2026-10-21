package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.util.PublicAddresses;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

public class PublicHttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(PublicHttpFetcher.class);
    private static final int MAX_REDIRECTS = 3;
    private static final long MEGABYTE = 1024L * 1024L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final String USER_AGENT = "hrva.cc-linkcheck/1.0 (+https://hrva.cc/.well-known/security.txt)";
    private static final String TEMP_FILE_PREFIX = "hrva-fetch-";
    private static final String NOT_ALLOWED = "not a public https address";
    private static final String TIMED_OUT = "timed out";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Predicate<String> targetPolicy;

    public PublicHttpFetcher(final Predicate<String> targetPolicy) {
        this.targetPolicy = targetPolicy;
    }

    public static Predicate<String> httpsPublicOnly() {
        return url -> isHttps(url) && PublicAddresses.isPublicTarget(url);
    }

    public static Predicate<String> httpOrHttpsPublicOnly() {
        return PublicAddresses::isPublicTarget;
    }

    public List<String> followRedirects(final String url, final int maxHops, final Duration budget) {
        final var deadline = System.nanoTime() + budget.toNanos();
        final var chain = new ArrayList<String>();
        chain.add(url);
        while (chain.size() <= maxHops && targetPolicy.test(chain.getLast())) {
            try {
                final var next = nextHop(chain.getLast(), deadline);
                if (next.isEmpty()) {
                    break;
                }
                chain.add(next.get());
            } catch (final UrlValidationException exception) {
                if (chain.size() == 1) {
                    throw exception;
                }
                log.debug("Redirect chain ends at hop {} reason={}", chain.size() - 1, exception.getMessage());
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

    private Optional<String> nextHop(final String url, final long deadline) {
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
        try {
            return Optional.of(URI.create(url).resolve(location.get()).toString());
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
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

    private static FetchResult result(final HttpResponse<InputStream> response, final long maxBytes, final long deadline) {
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

    private static Path download(final InputStream body, final long maxBytes, final long deadline) {
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

    private static Path createTempFile() {
        try {
            return Files.createTempFile(TEMP_FILE_PREFIX, ".tmp");
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

}
