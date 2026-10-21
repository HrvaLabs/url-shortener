package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.ReputationResult;
import cc.hrva.urlshortener.dto.ReputationResult.Verdict;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.service.impl.DefaultDomainReputationService;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class DefaultDomainReputationServiceTest {

    private static final String KEY = "dqs0secret0key";
    private static final String QUERY_SUFFIX = "." + KEY + ".dbl.dq.spamhaus.net";
    private static final String HOST = "login.secret-destination.example";
    private static final String DOMAIN = "secret-destination.example";
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    private final InMemoryRedis redis = new InMemoryRedis();
    private final Map<String, List<InetAddress>> answers = new ConcurrentHashMap<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();

    private AppProperties appProperties;
    private StringRedisTemplate redisTemplate;
    private DefaultDomainReputationService domainReputationService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setSpamhausDqsKey(KEY);
        redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        domainReputationService = serviceResolvingWith(this::resolve);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void missingKeyGivesNoneWithoutALookupOrALog(final String key, final CapturedOutput output) {
        appProperties.setSpamhausDqsKey(key);
        answer(DOMAIN, "127.0.1.4");

        final var result = domainReputationService.lookup(DOMAIN);

        assertThat(result).isEqualTo(ReputationResult.none());
        assertThat(queries).isEmpty();
        verifyNoInteractions(redisTemplate);
        assertThat(output).doesNotContain("DBL");
    }

    @ParameterizedTest
    @CsvSource({
            "127.0.1.4, BLOCK, phishing",
            "127.0.1.5, BLOCK, malware",
            "127.0.1.6, BLOCK, botnet",
            "127.0.1.2, FLAG, spam",
            "127.0.1.102, FLAG, abused legitimate domain",
            "127.0.1.103, FLAG, abused legitimate domain",
            "127.0.1.104, FLAG, abused legitimate domain",
            "127.0.1.105, FLAG, abused legitimate domain",
            "127.0.1.106, FLAG, abused legitimate domain"
    })
    void answerCodeDecidesTheVerdict(final String code, final Verdict verdict, final String type) {
        answer(DOMAIN, code);

        final var result = domainReputationService.lookup(DOMAIN);

        assertThat(result).isEqualTo(new ReputationResult(verdict, type));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.1.3", "127.0.1.101", "127.0.1.107", "127.0.0.2", "10.0.0.1"})
    void unknownAnswerCodeGivesNone(final String code) {
        answer(DOMAIN, code);

        final var result = domainReputationService.lookup(DOMAIN);

        assertThat(result).isEqualTo(ReputationResult.none());
        assertThat(cached(DOMAIN)).isEqualTo("none");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.255.255.255", "127.255.255.254", "127.255.255.252"})
    void errorCodeGivesNoneAndIsLoggedWithoutTheKeyOrTheName(final String code, final CapturedOutput output) {
        answer(DOMAIN, code);

        final var result = domainReputationService.lookup(DOMAIN);

        assertThat(result).isEqualTo(ReputationResult.none());
        assertThat(output)
                .contains("WARN")
                .contains("Spamhaus DBL")
                .contains(code)
                .doesNotContain(KEY)
                .doesNotContain(DOMAIN);
        assertThat(redis.keys()).isEmpty();
    }

    @Test
    void nxdomainGivesNoneAndIsCachedForAnHour() {
        final var result = domainReputationService.lookup(DOMAIN);

        assertThat(result).isEqualTo(ReputationResult.none());
        assertThat(queries).containsExactly(DOMAIN + QUERY_SUFFIX);
        assertThat(cached(DOMAIN)).isEqualTo("none");
        assertThat(redis.timeToLiveOfKeyContaining("dbl:" + DOMAIN)).isEqualTo(ONE_HOUR);
    }

    @Test
    void listedAnswerIsCachedForAnHour() {
        answer(DOMAIN, "127.0.1.4");

        domainReputationService.lookup(DOMAIN);

        assertThat(cached(DOMAIN)).isEqualTo("127.0.1.4");
        assertThat(redis.timeToLiveOfKeyContaining("dbl:" + DOMAIN)).isEqualTo(ONE_HOUR);
    }

    @Test
    void cachedAnswerSkipsTheResolver() {
        redisTemplate.opsForValue().set("dbl:" + DOMAIN, "127.0.1.4", ONE_HOUR);
        redisTemplate.opsForValue().set("dbl:clean.example", "none", ONE_HOUR);
        answer(DOMAIN, "127.0.1.2");

        assertThat(domainReputationService.lookup(DOMAIN)).isEqualTo(new ReputationResult(Verdict.BLOCK, "phishing"));
        assertThat(domainReputationService.lookup("clean.example")).isEqualTo(ReputationResult.none());
        assertThat(queries).isEmpty();
    }

    @Test
    void firstMatchingAddressDecides() {
        answer(DOMAIN, "127.0.0.9", "127.0.1.2", "127.0.1.4");

        final var result = domainReputationService.lookup(DOMAIN);

        assertThat(result).isEqualTo(new ReputationResult(Verdict.FLAG, "spam"));
        assertThat(cached(DOMAIN)).isEqualTo("127.0.1.2");
    }

    @Test
    void hostAndRegistrableDomainAreBothQueriedAndTheWorseDomainAnswerWins() {
        answer(HOST, "127.0.1.2");
        answer(DOMAIN, "127.0.1.4");

        final var result = domainReputationService.lookup(HOST);

        assertThat(result).isEqualTo(new ReputationResult(Verdict.BLOCK, "phishing"));
        assertThat(queries).containsExactlyInAnyOrder(HOST + QUERY_SUFFIX, DOMAIN + QUERY_SUFFIX);
        assertThat(cached(HOST)).isEqualTo("127.0.1.2");
        assertThat(cached(DOMAIN)).isEqualTo("127.0.1.4");
    }

    @Test
    void worseHostAnswerWinsOverAFlaggedDomain() {
        answer(HOST, "127.0.1.5");
        answer(DOMAIN, "127.0.1.104");

        final var result = domainReputationService.lookup(HOST);

        assertThat(result).isEqualTo(new ReputationResult(Verdict.BLOCK, "malware"));
    }

    @Test
    void flaggedHostWinsOverAnUnlistedDomain() {
        answer(HOST, "127.0.1.104");

        final var result = domainReputationService.lookup(HOST);

        assertThat(result).isEqualTo(new ReputationResult(Verdict.FLAG, "abused legitimate domain"));
    }

    @Test
    void hostThatIsItsOwnRegistrableDomainIsQueriedOnce() {
        domainReputationService.lookup(DOMAIN);

        assertThat(queries).containsExactly(DOMAIN + QUERY_SUFFIX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Login.Secret-Destination.EXAMPLE.", "https://LOGIN.secret-destination.example:443/path?q=1"})
    void hostIsNormalisedBeforeTheLookup(final String host) {
        domainReputationService.lookup(host);

        assertThat(queries).containsExactlyInAnyOrder(HOST + QUERY_SUFFIX, DOMAIN + QUERY_SUFFIX);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1.2.3.4", "203.0.113.7", "::1", "[::1]", "2001:db8::1", "[2001:db8::1]", "localhost",
            "not a host!", "-bad-.example", "   "})
    void ipLiteralsAndMalformedInputGiveNoneWithoutALookup(final String host) {
        final var result = domainReputationService.lookup(host);

        assertThat(result).isEqualTo(ReputationResult.none());
        assertThat(queries).isEmpty();
    }

    @Test
    void slowResolverGivesNoneAfterTwoSeconds() {
        final var release = new CountDownLatch(1);
        final var slowService = serviceResolvingWith(name -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return List.of(InetAddress.ofLiteral("127.0.1.4"));
        });

        try {
            final var started = System.nanoTime();
            final var result = slowService.lookup(HOST);
            final var elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(result).isEqualTo(ReputationResult.none());
            assertThat(elapsed).isBetween(Duration.ofMillis(1900), Duration.ofSeconds(8));
        } finally {
            release.countDown();
        }
    }

    @Test
    void resolverFailureGivesNoneWithoutLoggingTheQueryName(final CapturedOutput output) {
        final var failingService = serviceResolvingWith(name -> {
            throw new IllegalStateException("lookup failed for " + name);
        });

        final var result = failingService.lookup(HOST);

        assertThat(result).isEqualTo(ReputationResult.none());
        assertThat(output)
                .contains("IllegalStateException")
                .doesNotContain(KEY)
                .doesNotContain(DOMAIN);
        assertThat(redis.keys()).isEmpty();
    }

    @Test
    void redisReadFailureStillLooksUpWithoutCaching(final CapturedOutput output) {
        final var brokenRedis = mock(StringRedisTemplate.class);
        when(brokenRedis.opsForValue()).thenThrow(new RedisConnectionFailureException("redis down"));
        final var uncachedService = new DefaultDomainReputationService(appProperties, brokenRedis, this::resolve);
        answer(DOMAIN, "127.0.1.5");

        final var result = uncachedService.lookup(DOMAIN);

        assertThat(result).isEqualTo(new ReputationResult(Verdict.BLOCK, "malware"));
        assertThat(queries).containsExactly(DOMAIN + QUERY_SUFFIX);
        assertThat(output).doesNotContain(KEY).doesNotContain(DOMAIN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisWriteFailureStillReturnsTheAnswer(final CapturedOutput output) {
        final var failingRedis = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(failingRedis.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("redis down")).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        final var service = new DefaultDomainReputationService(appProperties, failingRedis, this::resolve);
        answer(DOMAIN, "127.0.1.6");

        final var result = service.lookup(DOMAIN);

        assertThat(result).isEqualTo(new ReputationResult(Verdict.BLOCK, "botnet"));
        assertThat(output).doesNotContain(KEY).doesNotContain(DOMAIN);
    }

    @Test
    void lookupAfterShutdownGivesNoneWithoutThrowing() {
        domainReputationService.shutdown();
        answer(DOMAIN, "127.0.1.4");

        final var result = domainReputationService.lookup(DOMAIN);

        assertThat(result).isEqualTo(ReputationResult.none());
        assertThat(queries).isEmpty();
    }

    private DefaultDomainReputationService serviceResolvingWith(final Function<String, List<InetAddress>> resolver) {
        return new DefaultDomainReputationService(appProperties, redisTemplate, resolver);
    }

    private List<InetAddress> resolve(final String name) {
        queries.add(name);
        return answers.getOrDefault(name, List.of());
    }

    private void answer(final String name, final String... addresses) {
        answers.put(name + QUERY_SUFFIX, Arrays.stream(addresses).map(InetAddress::ofLiteral).toList());
    }

    private String cached(final String name) {
        return redisTemplate.opsForValue().get("dbl:" + name);
    }

}
