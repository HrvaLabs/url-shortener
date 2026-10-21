package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.ReputationResult;
import cc.hrva.urlshortener.dto.ReputationResult.Verdict;
import cc.hrva.urlshortener.service.DomainReputationService;
import cc.hrva.urlshortener.util.DomainNames;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultDomainReputationService implements DomainReputationService {

    private static final String QUERY_ZONE = ".dbl.dq.spamhaus.net";
    private static final String CACHE_KEY_PREFIX = "dbl:";
    private static final String NOT_LISTED = "none";
    private static final String ERROR_CODE_PREFIX = "127.255.255.";
    private static final Duration CACHE_TIME_TO_LIVE = Duration.ofHours(1);
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration IDLE_THREAD_TIME_TO_LIVE = Duration.ofMinutes(1);
    private static final int LOOKUP_THREADS = 2;
    private static final int LOOKUP_QUEUE_CAPACITY = 32;
    private static final Comparator<ReputationResult> WORST_FIRST = Comparator.comparing(ReputationResult::verdict);
    private static final ReputationResult ABUSED_LEGITIMATE_DOMAIN = new ReputationResult(Verdict.FLAG, "abused legitimate domain");
    private static final Map<String, ReputationResult> ANSWERS = Map.ofEntries(
            Map.entry("127.0.1.4", new ReputationResult(Verdict.BLOCK, "phishing")),
            Map.entry("127.0.1.5", new ReputationResult(Verdict.BLOCK, "malware")),
            Map.entry("127.0.1.6", new ReputationResult(Verdict.BLOCK, "botnet")),
            Map.entry("127.0.1.2", new ReputationResult(Verdict.FLAG, "spam")),
            Map.entry("127.0.1.102", ABUSED_LEGITIMATE_DOMAIN),
            Map.entry("127.0.1.103", ABUSED_LEGITIMATE_DOMAIN),
            Map.entry("127.0.1.104", ABUSED_LEGITIMATE_DOMAIN),
            Map.entry("127.0.1.105", ABUSED_LEGITIMATE_DOMAIN),
            Map.entry("127.0.1.106", ABUSED_LEGITIMATE_DOMAIN));

    private final AppProperties appProperties;
    private final StringRedisTemplate redisTemplate;
    private final Function<String, List<InetAddress>> dblResolver;
    private final ExecutorService lookupExecutor = newLookupExecutor();

    @Override
    public ReputationResult lookup(final String host) {
        final var key = StringUtils.trimToEmpty(appProperties.getSpamhausDqsKey());
        if (key.isEmpty()) {
            return ReputationResult.none();
        }

        try {
            final var lookups = namesToQuery(host).stream()
                    .map(name -> (Callable<ReputationResult>) () -> lookupName(name, key))
                    .toList();
            if (lookups.isEmpty()) {
                return ReputationResult.none();
            }
            return lookupExecutor.invokeAll(lookups, LOOKUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).stream()
                    .map(DefaultDomainReputationService::resultOf)
                    .min(WORST_FIRST)
                    .orElseGet(ReputationResult::none);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ReputationResult.none();
        } catch (final RuntimeException exception) {
            log.warn("Spamhaus DBL lookup skipped exception={}", exception.getClass().getName());
            return ReputationResult.none();
        }
    }

    @PostConstruct
    public void logWhenOff() {
        if (StringUtils.isBlank(appProperties.getSpamhausDqsKey())) {
            log.info("Spamhaus DBL check is off: no SPAMHAUS_DQS_KEY");
        }
    }

    @PreDestroy
    public void shutdown() {
        lookupExecutor.shutdownNow();
    }

    private static List<String> namesToQuery(final String host) {
        return DomainNames.hostOf(host)
                .filter(DomainNames.HOST_NAME.asMatchPredicate())
                .filter(name -> !DomainNames.isIpLiteral(name))
                .map(name -> Stream.of(name, DomainNames.registrableDomain(name)).distinct().toList())
                .orElseGet(List::of);
    }

    private ReputationResult lookupName(final String name, final String key) {
        final var cacheKey = CACHE_KEY_PREFIX + name;
        try {
            final var cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return answerFor(cached);
            }
        } catch (final RuntimeException exception) {
            log.warn("Spamhaus DBL cache read failed, looking up without it exception={}", exception.getClass().getName());
            return answerFor(query(name, key));
        }

        final var code = query(name, key);
        if (!code.startsWith(ERROR_CODE_PREFIX) && !Thread.currentThread().isInterrupted()) {
            cache(cacheKey, code);
        }
        return answerFor(code);
    }

    private String query(final String name, final String key) {
        final var codes = dblResolver.apply(name + "." + key + QUERY_ZONE).stream()
                .filter(Inet4Address.class::isInstance)
                .map(InetAddress::getHostAddress)
                .filter(address -> ANSWERS.containsKey(address) || address.startsWith(ERROR_CODE_PREFIX))
                .toList();
        codes.stream()
                .filter(code -> code.startsWith(ERROR_CODE_PREFIX))
                .findFirst()
                .ifPresent(code -> log.warn("Spamhaus DBL answered with an error code={}", code));
        return codes.stream()
                .min(Comparator.comparing(DefaultDomainReputationService::answerFor, WORST_FIRST))
                .orElse(NOT_LISTED);
    }

    private void cache(final String cacheKey, final String code) {
        try {
            redisTemplate.opsForValue().set(cacheKey, code, CACHE_TIME_TO_LIVE);
        } catch (final RuntimeException exception) {
            log.warn("Spamhaus DBL cache write failed exception={}", exception.getClass().getName());
        }
    }

    private static ReputationResult answerFor(final String code) {
        return ANSWERS.getOrDefault(code, ReputationResult.none());
    }

    private static ReputationResult resultOf(final Future<ReputationResult> lookup) {
        return switch (lookup.state()) {
            case SUCCESS -> lookup.resultNow();
            case FAILED -> {
                log.warn("Spamhaus DBL lookup failed exception={}", lookup.exceptionNow().getClass().getName());
                yield ReputationResult.none();
            }
            case CANCELLED, RUNNING -> {
                log.warn("Spamhaus DBL lookup timed out after {} ms", LOOKUP_TIMEOUT.toMillis());
                yield ReputationResult.none();
            }
        };
    }

    private static ExecutorService newLookupExecutor() {
        final var executor = new ThreadPoolExecutor(LOOKUP_THREADS, LOOKUP_THREADS,
                IDLE_THREAD_TIME_TO_LIVE.toMillis(), TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(LOOKUP_QUEUE_CAPACITY),
                Thread.ofPlatform().name("dbl-lookup-", 0).daemon().factory());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

}
