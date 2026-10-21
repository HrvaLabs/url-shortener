package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.FetchResult;
import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.impl.DefaultDomainAgeService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDomainAgeServiceTest {

    private static final String HOST = "www.njuskaio.cam";
    private static final String KEY = "rdap:njuskaio.cam";
    private static final String BOOTSTRAP_KEY = "rdap:bootstrap";
    private static final String BOOTSTRAP_URL = "https://data.iana.org/rdap/dns.json";
    private static final String DOMAIN_URL = "https://rdap.example/domain/njuskaio.cam";
    private static final String UNKNOWN = "unknown";
    private static final long ONE_MEGABYTE = 1024L * 1024L;
    private static final Duration THREE_SECONDS = Duration.ofSeconds(3);
    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private static final String BOOTSTRAP = """
            {"version": "1.0", "services": [
              [["cam", "camera"], ["https://rdap.example/"]],
              [["com", "net"], ["https://rdap.verisign.com/com/v1/"]]
            ]}
            """;
    private static final String REGISTERED = """
            {"objectClassName": "domain", "ldhName": "njuskaio.cam", "events": [
              {"eventAction": "last changed", "eventDate": "2026-10-01T12:00:00Z"},
              {"eventAction": "registration", "eventDate": "2026-09-15T08:30:00Z"}
            ]}
            """;

    private final FakeFetcher fetcher = new FakeFetcher();

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private DefaultDomainAgeService domainAgeService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
        domainAgeService = new DefaultDomainAgeService(fetcher, redisTemplate, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        fetcher.release();
        domainAgeService.shutDown();
    }

    @Test
    void readsTheRegistrationDateAndCachesItForThirtyDays() {
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);
        fetcher.answer(DOMAIN_URL, REGISTERED);

        assertThat(domainAgeService.registeredOn(HOST)).contains(LocalDate.of(2026, 9, 15));

        verify(valueOperations).set(KEY, "2026-09-15", Duration.ofDays(30));
        verify(valueOperations).set(BOOTSTRAP_KEY, BOOTSTRAP, Duration.ofDays(1));
        assertThat(fetcher.requests).containsExactly(
                new Request(BOOTSTRAP_URL, ONE_MEGABYTE, THREE_SECONDS),
                new Request(DOMAIN_URL, ONE_MEGABYTE, THREE_SECONDS));
        assertThat(fetcher.bodies).hasSize(2).allMatch(Files::notExists);
    }

    @Test
    void usesTheCachedBootstrapWithoutFetchingIt() {
        when(valueOperations.get(BOOTSTRAP_KEY)).thenReturn(BOOTSTRAP);
        fetcher.answer(DOMAIN_URL, REGISTERED);

        assertThat(domainAgeService.registeredOn(HOST)).contains(LocalDate.of(2026, 9, 15));

        assertThat(fetcher.requestedUrls()).containsExactly(DOMAIN_URL);
        verify(valueOperations, never()).set(eq(BOOTSTRAP_KEY), anyString(), any(Duration.class));
    }

    @Test
    void picksTheFirstHttpsServerAndAddsTheMissingTrailingSlash() {
        fetcher.answer(BOOTSTRAP_URL, """
                {"services": [[["cam"], ["http://plain.example/", "https://rdap.example/v1", "https://other.example/"]]]}
                """);
        fetcher.answer("https://rdap.example/v1/domain/njuskaio.cam", REGISTERED);

        assertThat(domainAgeService.registeredOn(HOST)).contains(LocalDate.of(2026, 9, 15));

        assertThat(fetcher.requestedUrls()).containsExactly(BOOTSTRAP_URL, "https://rdap.example/v1/domain/njuskaio.cam");
    }

    @Test
    void readsTheRegistrationTimestampInUtc() {
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);
        fetcher.answer(DOMAIN_URL, """
                {"events": [{"eventAction": "registration", "eventDate": "2026-09-15T22:30:00-05:00"}]}
                """);

        assertThat(domainAgeService.registeredOn(HOST)).contains(LocalDate.of(2026, 9, 16));
    }

    @Test
    void topLevelDomainMissingFromTheBootstrapIsUnknownForADay() {
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);

        assertThat(domainAgeService.registeredOn("www.njuskalo.hr")).isEmpty();

        verify(valueOperations).set("rdap:njuskalo.hr", UNKNOWN, Duration.ofDays(1));
        assertThat(fetcher.requestedUrls()).containsExactly(BOOTSTRAP_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"events\": [{\"eventAction\": \"last changed\", \"eventDate\": \"2026-10-01T12:00:00Z\"}]}",
            "{\"objectClassName\": \"domain\"}",
            "{\"events\": [{\"eventAction\": \"registration\", \"eventDate\": \"15.09.2026.\"}]}"
    })
    void registryAnswerWithoutARegistrationDateIsUnknownForADay(final String answer) {
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);
        fetcher.answer(DOMAIN_URL, answer);

        assertThat(domainAgeService.registeredOn(HOST)).isEmpty();

        verify(valueOperations).set(KEY, UNKNOWN, Duration.ofDays(1));
    }

    @Test
    void fetchFailureIsEmptyAndCachedForAnHour() {
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);

        assertThat(domainAgeService.registeredOn(HOST)).isEmpty();

        assertThat(fetcher.requestedUrls()).containsExactly(BOOTSTRAP_URL, DOMAIN_URL);
        verify(valueOperations).set(KEY, UNKNOWN, Duration.ofHours(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>Service unavailable</html>", "", "[]"})
    void unreadableAnswerIsEmptyAndCachedForAnHour(final String answer) {
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);
        fetcher.answer(DOMAIN_URL, answer);

        assertThat(domainAgeService.registeredOn(HOST)).isEmpty();

        verify(valueOperations).set(KEY, UNKNOWN, Duration.ofHours(1));
        assertThat(fetcher.bodies).hasSize(2).allMatch(Files::notExists);
    }

    @Test
    void bootstrapFetchFailureIsCachedForAnHourWithoutCachingTheBootstrap() {
        assertThat(domainAgeService.registeredOn(HOST)).isEmpty();

        assertThat(fetcher.requestedUrls()).containsExactly(BOOTSTRAP_URL);
        verify(valueOperations).set(KEY, UNKNOWN, Duration.ofHours(1));
        verify(valueOperations, never()).set(eq(BOOTSTRAP_KEY), anyString(), any(Duration.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "", "{\"services\": []}", "{\"services\": [[[\"cam\"], [\"http://plain.example/\"]]]}"})
    void unusableBootstrapIsNotCached(final String bootstrap) {
        fetcher.answer(BOOTSTRAP_URL, bootstrap);

        assertThat(domainAgeService.registeredOn(HOST)).isEmpty();

        assertThat(fetcher.requestedUrls()).containsExactly(BOOTSTRAP_URL);
        verify(valueOperations).set(KEY, UNKNOWN, Duration.ofHours(1));
        verify(valueOperations, never()).set(eq(BOOTSTRAP_KEY), anyString(), any(Duration.class));
    }

    @Test
    void slowRegistryGivesUpWithinThreeSecondsAndIsCachedForAnHour() throws InterruptedException {
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);
        fetcher.hang(DOMAIN_URL);

        final var startedAt = System.nanoTime();
        final var registeredOn = domainAgeService.registeredOn(HOST);
        final var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(registeredOn).isEmpty();
        assertThat(elapsed).isGreaterThanOrEqualTo(THREE_SECONDS).isLessThan(Duration.ofSeconds(4));
        verify(valueOperations).set(KEY, UNKNOWN, Duration.ofHours(1));
        assertThat(fetcher.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cachedDateIsReturnedWithoutFetching() {
        when(valueOperations.get(KEY)).thenReturn("2026-09-15");

        assertThat(domainAgeService.registeredOn(HOST)).contains(LocalDate.of(2026, 9, 15));

        assertThat(fetcher.requests).isEmpty();
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void cachedUnknownIsReturnedWithoutFetching() {
        when(valueOperations.get(KEY)).thenReturn(UNKNOWN);

        assertThat(domainAgeService.registeredOn(HOST)).isEmpty();

        assertThat(fetcher.requests).isEmpty();
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void unreadableCachedValueIsLookedUpAgain() {
        when(valueOperations.get(KEY)).thenReturn("garbage");
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);
        fetcher.answer(DOMAIN_URL, REGISTERED);

        assertThat(domainAgeService.registeredOn(HOST)).contains(LocalDate.of(2026, 9, 15));

        verify(valueOperations).set(KEY, "2026-09-15", Duration.ofDays(30));
        verify(valueOperations).set(BOOTSTRAP_KEY, BOOTSTRAP, Duration.ofDays(1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "203.0.113.7", "1.2.3.4.", "::1", "2001:db8::1", "[2001:db8::1]", "localhost", "cam", "exa mple.com",
            "https://njuskaio.cam/login", "njuskaio.cam:443", "-njuskaio.cam", "njuškaio.cam"
    })
    void skipsIpLiteralsAndHostsWithoutARegistrableDomain(final String host) {
        assertThat(domainAgeService.registeredOn(host)).isEmpty();
        assertThat(domainAgeService.isYoung(host)).isFalse();

        assertThat(fetcher.requests).isEmpty();
        verifyNoInteractions(valueOperations);
    }

    @Test
    void normalisesTheHostBeforeLookingItUp() {
        when(valueOperations.get(KEY)).thenReturn("2026-09-15");

        assertThat(domainAgeService.registeredOn("WWW.NjuskaIO.CAM.")).contains(LocalDate.of(2026, 9, 15));
    }

    @Test
    void redisReadFailureLooksUpWithoutCaching() {
        when(valueOperations.get(KEY)).thenThrow(new RedisConnectionFailureException("Redis is down"));
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);
        fetcher.answer(DOMAIN_URL, REGISTERED);

        assertThat(domainAgeService.registeredOn(HOST)).contains(LocalDate.of(2026, 9, 15));

        assertThat(fetcher.requestedUrls()).containsExactly(BOOTSTRAP_URL, DOMAIN_URL);
        verify(valueOperations, never()).get(BOOTSTRAP_KEY);
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisWriteFailureStillReturnsTheDate() {
        doThrow(new RedisConnectionFailureException("Redis is down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);
        fetcher.answer(DOMAIN_URL, REGISTERED);

        assertThat(domainAgeService.registeredOn(HOST)).contains(LocalDate.of(2026, 9, 15));
    }

    @Test
    void redisFailureOnABootstrapReadStillLooksUp() {
        when(valueOperations.get(BOOTSTRAP_KEY)).thenThrow(new RedisConnectionFailureException("Redis is down"));
        fetcher.answer(BOOTSTRAP_URL, BOOTSTRAP);
        fetcher.answer(DOMAIN_URL, REGISTERED);

        assertThat(domainAgeService.registeredOn(HOST)).contains(LocalDate.of(2026, 9, 15));

        verify(valueOperations).set(KEY, "2026-09-15", Duration.ofDays(30));
    }

    @ParameterizedTest
    @CsvSource({"2026-09-22, true", "2026-09-21, false", "2026-10-21, true", "unknown, false"})
    void youngMeansRegisteredLessThanThirtyDaysAgo(final String cached, final boolean young) {
        when(valueOperations.get(KEY)).thenReturn(cached);

        assertThat(domainAgeService.isYoung(HOST)).isEqualTo(young);
    }

    private record Request(String url, long maxBytes, Duration requestTimeout) {}

    private static final class FakeFetcher extends PublicHttpFetcher {

        private final Map<String, String> answers = new ConcurrentHashMap<>();
        private final Set<String> hangingUrls = ConcurrentHashMap.newKeySet();
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final List<Path> bodies = new CopyOnWriteArrayList<>();
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);

        private FakeFetcher() {
            super(url -> true);
        }

        @Override
        public FetchResult fetch(final String url, final long maxBytes, final String etag, final String lastModified,
                final Duration requestTimeout) {
            requests.add(new Request(url, maxBytes, requestTimeout));
            if (hangingUrls.contains(url)) {
                awaitRelease();
            }
            return Optional.ofNullable(answers.get(url))
                    .map(answer -> new FetchResult(false, bodyOf(answer), null, null))
                    .orElseThrow(() -> new UrlValidationException("HTTP 404"));
        }

        private void answer(final String url, final String body) {
            answers.put(url, body);
        }

        private void hang(final String url) {
            hangingUrls.add(url);
        }

        private void release() {
            released.countDown();
        }

        private List<String> requestedUrls() {
            return requests.stream().map(Request::url).toList();
        }

        private void awaitRelease() {
            try {
                released.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            throw new UrlValidationException("timed out");
        }

        private Path bodyOf(final String answer) {
            try {
                final var body = Files.createTempFile("rdap-test-", ".json");
                Files.writeString(body, answer);
                bodies.add(body);
                return body;
            } catch (final IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

    }

}
