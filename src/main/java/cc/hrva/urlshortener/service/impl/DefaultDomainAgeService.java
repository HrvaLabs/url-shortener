package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.util.DomainNames;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultDomainAgeService implements DomainAgeService {

    private static final String KEY_PREFIX = "rdap:";
    private static final String BOOTSTRAP_KEY = KEY_PREFIX + "bootstrap";
    private static final String BOOTSTRAP_URL = "https://data.iana.org/rdap/dns.json";
    private static final String UNKNOWN = "unknown";
    private static final String REGISTRATION = "registration";
    private static final String HTTPS = "https://";
    private static final Pattern CLIENT_ERROR = Pattern.compile("HTTP 4\\d\\d");
    private static final int ISO_DATE_LENGTH = 10;
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REGISTERED_TIME_TO_LIVE = Duration.ofDays(30);
    private static final Duration UNKNOWN_TIME_TO_LIVE = Duration.ofDays(1);
    private static final Duration FAILED_TIME_TO_LIVE = Duration.ofHours(1);
    private static final Duration BOOTSTRAP_TIME_TO_LIVE = Duration.ofDays(1);
    private static final int YOUNG_DAYS = 30;
    private static final int LOOKUP_THREADS = 2;
    private static final int LOOKUP_QUEUE_CAPACITY = 16;

    private final PublicHttpFetcher publicHttpFetcher;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final ExecutorService lookupExecutor = new ThreadPoolExecutor(LOOKUP_THREADS, LOOKUP_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(LOOKUP_QUEUE_CAPACITY), Thread.ofPlatform().name("rdap-lookup-", 0).daemon().factory());

    @Override
    public Optional<LocalDate> registeredOn(final String host) {
        return registrableDomainOf(host).flatMap(this::registeredOnDomain);
    }

    @Override
    public boolean isYoung(final String host) {
        final var youngAfter = LocalDate.now(clock).minusDays(YOUNG_DAYS);
        return registeredOn(host).filter(registeredOn -> registeredOn.isAfter(youngAfter)).isPresent();
    }

    @PreDestroy
    public void shutDown() {
        lookupExecutor.shutdownNow();
    }

    private static Optional<String> registrableDomainOf(final String host) {
        return Optional.ofNullable(host)
                .map(value -> Strings.CS.removeEnd(value.toLowerCase(Locale.ROOT), "."))
                .filter(value -> DomainNames.HOST_NAME.matcher(value).matches())
                .filter(value -> !StringUtils.isNumeric(StringUtils.substringAfterLast(value, ".")))
                .map(DomainNames::registrableDomain);
    }

    private Optional<LocalDate> registeredOnDomain(final String domain) {
        final var cached = read(KEY_PREFIX + domain);
        if (UNKNOWN.equals(cached.value())) {
            return Optional.empty();
        }

        final var cachedDate = isoDateOf(cached.value());
        if (cachedDate.isPresent()) {
            return cachedDate;
        }

        return lookUp(domain, cached.readable());
    }

    private Optional<LocalDate> lookUp(final String domain, final boolean caching) {
        final var started = new AtomicBoolean();
        final var lookup = new FutureTask<>(() -> {
            started.set(true);
            return registrationDateOf(domain, caching);
        });
        try {
            lookupExecutor.execute(lookup);
            final var registeredOn = lookup.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (caching) {
                write(KEY_PREFIX + domain, registeredOn.map(LocalDate::toString).orElse(UNKNOWN),
                        registeredOn.isPresent() ? REGISTERED_TIME_TO_LIVE : UNKNOWN_TIME_TO_LIVE);
            }
            return registeredOn;
        } catch (final RejectedExecutionException exception) {
            log.warn("RDAP lookups are all busy, domain age unknown");
            return Optional.empty();
        } catch (final TimeoutException exception) {
            lookup.cancel(true);
            if (!started.get()) {
                log.warn("RDAP lookup waited too long for a free worker, domain age unknown");
                return Optional.empty();
            }
            log.warn("RDAP lookup timed out, domain age unknown for an hour");
            return failed(domain, caching);
        } catch (final ExecutionException exception) {
            logFailure(exception.getCause());
            return failed(domain, caching);
        } catch (final InterruptedException exception) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private Optional<LocalDate> failed(final String domain, final boolean caching) {
        if (caching) {
            write(KEY_PREFIX + domain, UNKNOWN, FAILED_TIME_TO_LIVE);
        }
        return Optional.empty();
    }

    private static void logFailure(final Throwable failure) {
        if (failure instanceof UrlValidationException && failure.getMessage() != null && CLIENT_ERROR.matcher(failure.getMessage()).matches()) {
            log.info("RDAP registry refused the lookup, domain age unknown for an hour reason={}", failure.getMessage());
            return;
        }
        log.warn("RDAP lookup failed, domain age unknown for an hour reason={}: {}", failure.getClass().getSimpleName(), failure.getMessage());
    }

    private Optional<LocalDate> registrationDateOf(final String domain, final boolean caching) {
        final var baseUrl = rdapBaseUrls(caching).get(StringUtils.substringAfterLast(domain, "."));
        if (baseUrl == null) {
            return Optional.empty();
        }

        final var answer = jsonMapper.readTree(fetch(baseUrl + "domain/" + domain));
        if (!answer.isObject()) {
            throw new IllegalStateException("not an RDAP domain answer");
        }

        return answer.path("events").valueStream()
                .filter(event -> event.path("eventAction").stringValueOpt().filter(REGISTRATION::equals).isPresent())
                .findFirst()
                .flatMap(event -> event.path("eventDate").stringValueOpt())
                .flatMap(DefaultDomainAgeService::utcDateOf);
    }

    private Map<String, String> rdapBaseUrls(final boolean caching) {
        final var cached = caching ? read(BOOTSTRAP_KEY).value() : null;
        if (cached != null) {
            return baseUrlsOf(cached);
        }

        final var bootstrap = fetch(BOOTSTRAP_URL);
        final var baseUrls = baseUrlsOf(bootstrap);
        if (caching) {
            write(BOOTSTRAP_KEY, bootstrap, BOOTSTRAP_TIME_TO_LIVE);
        }
        return baseUrls;
    }

    private Map<String, String> baseUrlsOf(final String bootstrap) {
        final var baseUrls = jsonMapper.readTree(bootstrap).path("services").valueStream()
                .flatMap(DefaultDomainAgeService::baseUrlsOfService)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
        if (baseUrls.isEmpty()) {
            throw new IllegalStateException("the RDAP bootstrap lists no https servers");
        }
        return baseUrls;
    }

    private static Stream<Map.Entry<String, String>> baseUrlsOfService(final JsonNode service) {
        return httpsBaseUrlOf(service.path(1)).stream()
                .flatMap(baseUrl -> service.path(0).valueStream()
                        .flatMap(topLevelDomain -> topLevelDomain.stringValueOpt().stream())
                        .map(topLevelDomain -> Map.entry(topLevelDomain.toLowerCase(Locale.ROOT), baseUrl)));
    }

    private static Optional<String> httpsBaseUrlOf(final JsonNode urls) {
        return urls.valueStream()
                .flatMap(url -> url.stringValueOpt().stream())
                .filter(url -> Strings.CI.startsWith(url, HTTPS))
                .findFirst()
                .map(url -> Strings.CS.appendIfMissing(url, "/"));
    }

    private String fetch(final String url) {
        final var body = publicHttpFetcher.fetch(url, MAX_BYTES, null, null, TIMEOUT).body();
        if (body == null) {
            throw new IllegalStateException("RDAP answered without a body");
        }

        try {
            return Files.readString(body);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            delete(body);
        }
    }

    private static void delete(final Path body) {
        try {
            Files.deleteIfExists(body);
        } catch (final IOException exception) {
            log.warn("Could not delete the temporary RDAP answer {}", body, exception);
        }
    }

    private Cached read(final String key) {
        try {
            return new Cached(true, redisTemplate.opsForValue().get(key));
        } catch (final RuntimeException exception) {
            log.warn("RDAP cache read failed, looking up without caching exception={}", exception.getClass().getName());
            return new Cached(false, null);
        }
    }

    private void write(final String key, final String value, final Duration timeToLive) {
        try {
            redisTemplate.opsForValue().set(key, value, timeToLive);
        } catch (final RuntimeException exception) {
            log.warn("RDAP cache write failed exception={}", exception.getClass().getName());
        }
    }

    private static Optional<LocalDate> isoDateOf(final String value) {
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(LocalDate.parse(value));
        } catch (final DateTimeParseException exception) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> utcDateOf(final String timestamp) {
        try {
            return Optional.of(OffsetDateTime.parse(timestamp).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate());
        } catch (final DateTimeParseException exception) {
            return timestamp.length() >= ISO_DATE_LENGTH ? isoDateOf(timestamp.substring(0, ISO_DATE_LENGTH)) : Optional.empty();
        }
    }

    private record Cached(boolean readable, String value) {}

}
