package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.notification.HostPacer;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
public class DefaultDnsFilterService implements DnsFilterService {

    private static final String CACHE_KEY_PREFIX = "dnsfilter:";
    private static final String PACER_KEY = "dns-filters";
    private static final String TRAILING_DOT = ".";
    private static final Duration ANSWER_GRACE = Duration.ofMillis(500);

    private final AppProperties.DnsFilters dnsFilters;
    private final SettingsService settingsService;
    private final DnsQuery dnsQuery;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final HostPacer pacer;

    public DefaultDnsFilterService(final AppProperties appProperties, final SettingsService settingsService, final DnsQuery dnsQuery,
            final StringRedisTemplate redisTemplate, final JsonMapper jsonMapper, final Clock clock, final HostPacer.Sleeper hostPacerSleeper) {
        this.dnsFilters = appProperties.getDnsFilters();
        this.settingsService = settingsService;
        this.dnsQuery = dnsQuery;
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.pacer = new HostPacer(clock, hostPacerSleeper, dnsFilters.getPace());
    }

    @Override
    public DnsFilterReport lookup(final String host) {
        final var asciiHost = normalise(host);
        final var filters = dnsFilters.getFilters().stream()
                .filter(filter -> settingsService.isOn(filter.getProvider().settingKey()))
                .toList();
        final var cacheKey = CACHE_KEY_PREFIX + asciiHost;
        try {
            final var cached = cachedReport(cacheKey, filters);
            if (cached.isPresent()) {
                return cached.get();
            }
        } catch (final RuntimeException exception) {
            log.warn("DNS filter cache read failed, looking up without it exception={}", exception.getClass().getName());
            return ask(asciiHost, filters).report();
        }
        final var outcome = ask(asciiHost, filters);
        if (outcome.cacheable()) {
            cache(cacheKey, outcome.report());
        }
        return outcome.report();
    }

    private Optional<DnsFilterReport> cachedReport(final String cacheKey, final List<AppProperties.DnsFilters.Filter> filters) {
        final var json = redisTemplate.opsForValue().get(cacheKey);
        if (json == null) {
            return Optional.empty();
        }
        final var report = jsonMapper.readValue(json, DnsFilterReport.class);
        final var answers = filters.stream()
                .map(filter -> report.answers().stream().filter(answer -> answer.provider() == filter.getProvider()).findFirst())
                .toList();
        if (answers.stream().anyMatch(Optional::isEmpty)) {
            return Optional.empty();
        }
        return Optional.of(new DnsFilterReport(report.host(), report.exists(), answers.stream().map(Optional::get).toList()));
    }

    private void cache(final String cacheKey, final DnsFilterReport report) {
        try {
            redisTemplate.opsForValue().set(cacheKey, jsonMapper.writeValueAsString(report), dnsFilters.getCacheFor());
        } catch (final RuntimeException exception) {
            log.warn("DNS filter cache write failed exception={}", exception.getClass().getName());
        }
    }

    private Outcome ask(final String asciiHost, final List<AppProperties.DnsFilters.Filter> filters) {
        pacer.awaitTurn(PACER_KEY);
        final var filterFutures = filters.stream().map(filter -> send(filter.getAddress(), asciiHost)).toList();
        final var referenceFutures = dnsFilters.getReferences().stream().map(address -> send(address, asciiHost)).toList();
        awaitAll(filterFutures, referenceFutures);
        final var referenceReplies = referenceFutures.stream().map(DefaultDnsFilterService::replyOf).toList();
        final var exists = DnsFilterClassifier.exists(referenceReplies, blockAnswers());
        final var answers = IntStream.range(0, filters.size())
                .mapToObj(index -> DnsFilterClassifier.classify(filters.get(index), replyOf(filterFutures.get(index)), referenceReplies))
                .toList();
        final var report = new DnsFilterReport(asciiHost, exists, answers);
        return new Outcome(report, !report.anyUnanswered() && referenceReplies.stream().anyMatch(Optional::isPresent));
    }

    private Set<String> blockAnswers() {
        return dnsFilters.getFilters().stream().flatMap(filter -> filter.getBlockAnswers().stream()).collect(Collectors.toSet());
    }

    private CompletableFuture<DnsReply> send(final String address, final String asciiHost) {
        try {
            return dnsQuery.ask(address, asciiHost, dnsFilters.getTimeout());
        } catch (final RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void awaitAll(final List<CompletableFuture<DnsReply>> filterFutures, final List<CompletableFuture<DnsReply>> referenceFutures) {
        final var all = CompletableFuture.allOf(Stream.concat(filterFutures.stream(), referenceFutures.stream())
                .toArray(CompletableFuture[]::new));
        try {
            all.get(dnsFilters.getTimeout().plus(ANSWER_GRACE).toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException | TimeoutException exception) {
            log.debug("DNS filter lookup ended with unanswered resolvers exception={}", exception.getClass().getName());
        }
    }

    private static Optional<DnsReply> replyOf(final CompletableFuture<DnsReply> future) {
        if (!future.isDone() || future.isCompletedExceptionally()) {
            return Optional.empty();
        }
        return Optional.ofNullable(future.getNow(null));
    }

    private static String normalise(final String host) {
        return DomainNames.toAscii(StringUtils.removeEnd(StringUtils.strip(host).toLowerCase(Locale.ROOT), TRAILING_DOT));
    }

    private record Outcome(DnsFilterReport report, boolean cacheable) {

    }

}
