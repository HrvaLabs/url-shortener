package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.service.rdap.DomainRecord;
import cc.hrva.urlshortener.service.rdap.RdapAnswers;
import cc.hrva.urlshortener.util.DomainNames;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultDomainAgeService implements DomainAgeService {

    private static final String KEY_PREFIX = "rdap:";
    private static final String BOOTSTRAP_KEY = KEY_PREFIX + "bootstrap";
    private static final String RECORD_KEY_PREFIX = KEY_PREFIX + "info:";
    private static final String NOT_FOUND = "HTTP 404";
    private static final String BOOTSTRAP_URL = "https://data.iana.org/rdap/dns.json";
    private static final String UNKNOWN = "unknown";
    private static final String HTTPS = "https://";
    private static final Pattern CLIENT_ERROR = Pattern.compile("HTTP 4\\d\\d");
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REGISTERED_TIME_TO_LIVE = Duration.ofDays(30);
    private static final Duration UNKNOWN_TIME_TO_LIVE = Duration.ofDays(1);
    private static final Duration FAILED_TIME_TO_LIVE = Duration.ofHours(1);
    private static final Duration BOOTSTRAP_TIME_TO_LIVE = Duration.ofDays(1);
    private static final Duration RECORD_TIME_TO_LIVE = Duration.ofDays(1);
    private static final Duration RECORDS_DEADLINE = Duration.ofSeconds(20);
    private static final int YOUNG_DAYS = 30;
    private static final int LOOKUP_THREADS = 2;
    private static final int LOOKUP_QUEUE_CAPACITY = 16;
    private static final int RECORD_THREADS = 4;
    private static final int RECORD_QUEUE_CAPACITY = 1000;

    private final PublicHttpFetcher publicHttpFetcher;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final ExecutorService lookupExecutor = new ThreadPoolExecutor(LOOKUP_THREADS, LOOKUP_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(LOOKUP_QUEUE_CAPACITY), Thread.ofPlatform().name("rdap-lookup-", 0).daemon().factory());
    private final ExecutorService recordExecutor = new ThreadPoolExecutor(RECORD_THREADS, RECORD_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(RECORD_QUEUE_CAPACITY), Thread.ofPlatform().name("rdap-record-", 0).daemon().factory());

    @Override
    public Optional<LocalDate> registeredOn(final String host) {
        return registryDomainOf(host).flatMap(this::registeredOnDomain);
    }

    @Override
    public boolean isYoung(final String host) {
        final var youngAfter = LocalDate.now(clock).minusDays(YOUNG_DAYS);
        return registeredOn(host).filter(registeredOn -> registeredOn.isAfter(youngAfter)).isPresent();
    }

    @Override
    public Map<String, DomainRecord> records(final Collection<String> domains, final boolean fresh) {
        return records(domains, fresh, RECORDS_DEADLINE);
    }

    public Map<String, DomainRecord> records(final Collection<String> domains, final boolean fresh, final Duration deadline) {
        final var endsAt = System.nanoTime() + deadline.toNanos();
        final var registryDomains = new LinkedHashMap<String, Optional<String>>();
        domains.stream().filter(Objects::nonNull).forEach(domain -> registryDomains.computeIfAbsent(domain, DefaultDomainAgeService::registryDomainOf));
        final var found = new HashMap<String, DomainRecord>();
        final var pending = new ArrayList<String>();
        registryDomains.values().stream().flatMap(Optional::stream).distinct().forEach(domain -> {
            final var cached = fresh ? Optional.<DomainRecord>empty() : cachedRecord(domain);
            cached.ifPresentOrElse(record -> found.put(domain, record), () -> pending.add(domain));
        });
        if (!pending.isEmpty()) {
            found.putAll(lookUpRecords(pending, endsAt));
        }
        final var records = new LinkedHashMap<String, DomainRecord>();
        registryDomains.forEach((domain, registryDomain) -> records.put(domain, registryDomain
                .map(name -> found.getOrDefault(name, DomainRecord.failed(name)))
                .orElseGet(() -> DomainRecord.noRdapServer(domain))));
        return records;
    }

    @PreDestroy
    public void shutDown() {
        lookupExecutor.shutdownNow();
        recordExecutor.shutdownNow();
    }

    private Optional<DomainRecord> cachedRecord(final String domain) {
        final var cached = read(RECORD_KEY_PREFIX + domain).value();
        if (cached == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(cached, DomainRecord.class)).filter(record -> record.status() != DomainRecord.Status.FAILED);
        } catch (final JacksonException exception) {
            return Optional.empty();
        }
    }

    private Map<String, DomainRecord> lookUpRecords(final List<String> domains, final long endsAt) {
        final Map<String, String> baseUrls;
        try {
            baseUrls = rdapBaseUrls(true);
        } catch (final RuntimeException exception) {
            log.warn("RDAP bootstrap failed, no registry lookups reason={}: {}", exception.getClass().getSimpleName(), exception.getMessage());
            return Map.of();
        }

        final var lookups = new LinkedHashMap<String, FutureTask<DomainRecord>>();
        for (final var domain : domains) {
            final var lookup = new FutureTask<>(() -> recordOf(domain, baseUrls, endsAt));
            try {
                recordExecutor.execute(lookup);
                lookups.put(domain, lookup);
            } catch (final RejectedExecutionException exception) {
                log.warn("RDAP record lookups are all busy, lookup failed domain={}", domain);
            }
        }

        final var records = new HashMap<String, DomainRecord>();
        lookups.forEach((domain, lookup) -> {
            try {
                records.put(domain, lookup.get(Math.max(0L, endsAt - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (final TimeoutException exception) {
                lookup.cancel(false);
                log.warn("RDAP record lookup missed the deadline domain={}", domain);
            } catch (final ExecutionException exception) {
                logRecordFailure(domain, exception.getCause());
            } catch (final InterruptedException exception) {
                lookup.cancel(false);
                Thread.currentThread().interrupt();
            }
        });
        return records;
    }

    private DomainRecord recordOf(final String domain, final Map<String, String> baseUrls, final long endsAt) {
        if (System.nanoTime() >= endsAt) {
            return DomainRecord.failed(domain);
        }
        final var baseUrl = baseUrls.get(StringUtils.substringAfterLast(domain, "."));
        if (baseUrl == null) {
            return cachedAs(DomainRecord.noRdapServer(domain));
        }

        try {
            final var answer = jsonMapper.readTree(fetch(baseUrl + "domain/" + domain));
            if (!answer.isObject()) {
                throw new IllegalStateException("not an RDAP domain answer");
            }
            final var record = cachedAs(RdapAnswers.found(domain, answer));
            if (record.registeredOn() != null) {
                write(KEY_PREFIX + domain, record.registeredOn().toString(), REGISTERED_TIME_TO_LIVE);
            }
            return record;
        } catch (final UrlValidationException exception) {
            if (NOT_FOUND.equals(exception.getMessage())) {
                return cachedAs(DomainRecord.notFound(domain));
            }
            logRecordFailure(domain, exception);
            return DomainRecord.failed(domain);
        } catch (final RuntimeException exception) {
            logRecordFailure(domain, exception);
            return DomainRecord.failed(domain);
        }
    }

    private static void logRecordFailure(final String domain, final Throwable failure) {
        log.warn("RDAP record lookup failed, not cached domain={} reason={}: {}", domain, failure.getClass().getSimpleName(), failure.getMessage());
    }

    private DomainRecord cachedAs(final DomainRecord record) {
        write(RECORD_KEY_PREFIX + record.domain(), jsonMapper.writeValueAsString(record), RECORD_TIME_TO_LIVE);
        return record;
    }

    private static Optional<String> registryDomainOf(final String host) {
        return Optional.ofNullable(host)
                .map(value -> Strings.CS.removeEnd(value.toLowerCase(Locale.ROOT), "."))
                .filter(value -> DomainNames.HOST_NAME.matcher(value).matches())
                .filter(value -> !StringUtils.isNumeric(StringUtils.substringAfterLast(value, ".")))
                .map(DomainNames::registryDomain);
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

        return RdapAnswers.registeredOn(answer);
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

    private record Cached(boolean readable, String value) {}

}
