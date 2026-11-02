package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.FetchResult;
import cc.hrva.urlshortener.client.PageFetcher;
import cc.hrva.urlshortener.dto.LinkPreviewResponse;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.LinkPreviewOutcome.Busy;
import cc.hrva.urlshortener.service.LinkPreviewOutcome.Limited;
import cc.hrva.urlshortener.service.LinkPreviewOutcome.Ready;
import cc.hrva.urlshortener.service.impl.DefaultLinkPreviewService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultLinkPreviewServiceLimitsTest {

    private static final long LINK_ID = 7L;
    private static final String URL = "https://shop.example/secret-path?token=abc";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String PAGE = "<html><head><meta property=\"og:title\" content=\"Shop\"></head></html>";
    private static final LinkPreviewResponse PREVIEW = new LinkPreviewResponse(URL, "Shop", null, null);

    private final Map<String, Long> counts = new ConcurrentHashMap<>();
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final AtomicInteger fetches = new AtomicInteger();
    private final RateLimiter rateLimiter = (key, window) -> counts.merge(key, 1L, Long::sum);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock();
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(DefaultLinkPreviewService.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(invocation -> cache.get(invocation.<String>getArgument(0)));
        doAnswer(invocation -> cache.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        logEvents.start();
        serviceLogger.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logEvents);
    }

    @Test
    void aPreviewIsCachedForTenMinutesAndServedWithoutFetchingAgain() {
        final var service = service(page());

        assertThat(service.preview(LINK_ID, URL, CLIENT_IP)).isEqualTo(new Ready(PREVIEW));
        assertThat(service.preview(LINK_ID, URL, "198.51.100.1")).isEqualTo(new Ready(PREVIEW));

        assertThat(fetches).hasValue(1);
        verify(values).set(anyString(), anyString(), eq(Duration.ofMinutes(10)));
    }

    @Test
    void aNetworkOverItsLimitIsAskedToWait() {
        final var service = service(page());

        for (int request = 0; request < 30; request++) {
            assertThat(service.preview(LINK_ID, URL, CLIENT_IP)).isInstanceOf(Ready.class);
        }

        assertThat(service.preview(LINK_ID, URL, CLIENT_IP)).isEqualTo(new Limited(Duration.ofMinutes(1)));
        assertThat(service.preview(LINK_ID, URL, "203.0.113.8")).isInstanceOf(Ready.class);
    }

    @Test
    void aLinkOverItsFetchLimitIsAskedToWait() {
        when(values.get(anyString())).thenReturn(null);
        final var service = service(page());

        for (int request = 0; request < 10; request++) {
            assertThat(service.preview(LINK_ID, URL, "198.51.100." + request)).isInstanceOf(Ready.class);
        }

        assertThat(service.preview(LINK_ID, URL, "198.51.100.99")).isEqualTo(new Limited(Duration.ofMinutes(10)));
        assertThat(service.preview(8L, URL, "198.51.100.99")).isInstanceOf(Ready.class);
        assertThat(fetches).hasValue(11);
    }

    @Test
    void aLimiterThatIsDownTurnsThePreviewAwayWithoutFetching() {
        final RateLimiter down = (key, window) -> {
            throw new RateLimiterUnavailableException(new IllegalStateException("redis down"));
        };
        final var service = new DefaultLinkPreviewService(page(), down, redisTemplate, JsonMapper.builder().build());

        assertThat(service.preview(LINK_ID, URL, CLIENT_IP)).isEqualTo(new Busy());
        assertThat(fetches).hasValue(0);
        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Link preview rate limit unavailable, refusing the preview linkId=" + LINK_ID);
    }

    @Test
    void aCacheThatIsDownStillFetchesThePreview() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        doAnswer(invocation -> {
            throw new RedisConnectionFailureException("down");
        }).when(values).set(anyString(), anyString(), any(Duration.class));

        assertThat(service(page()).preview(LINK_ID, URL, CLIENT_IP)).isEqualTo(new Ready(PREVIEW));
    }

    @Test
    void moreThanEightFetchesAtOnceAreTurnedAway() throws Exception {
        final var inFlight = new CountDownLatch(8);
        final var release = new CountDownLatch(1);
        final var service = service((url, maxBytes, timeout) -> {
            inFlight.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return page().fetch(url, maxBytes, timeout);
        });
        final var answers = new ArrayList<Future<LinkPreviewOutcome>>();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int link = 0; link < 8; link++) {
                final var linkId = (long) link;
                answers.add(executor.submit(() -> service.preview(linkId, URL + linkId, "198.51.100." + linkId)));
            }
            assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.preview(99L, URL + "99", "198.51.100.99")).isEqualTo(new Busy());

            release.countDown();
            for (final var answer : answers) {
                assertThat(answer.get(5, TimeUnit.SECONDS)).isInstanceOf(Ready.class);
            }
        }
        assertThat(service.preview(99L, URL + "99", "198.51.100.99")).isInstanceOf(Ready.class);
    }

    @Test
    void logsNameOnlyTheDomainAndTheLinkId() {
        final var service = service((url, maxBytes, timeout) -> {
            throw new UrlValidationException("HTTP 404");
        });

        assertThat(service.preview(LINK_ID, URL, CLIENT_IP)).isEqualTo(new Ready(new LinkPreviewResponse(URL, null, null, null)));

        assertThat(logEvents.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("linkId=7").contains("domain=shop.example")
                    .doesNotContain("secret-path").doesNotContain("token");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private DefaultLinkPreviewService service(final PageFetcher pageFetcher) {
        return new DefaultLinkPreviewService(pageFetcher, rateLimiter, redisTemplate, JsonMapper.builder().build());
    }

    private PageFetcher page() {
        return (url, maxBytes, timeout) -> {
            fetches.incrementAndGet();
            try {
                final var body = Files.createTempFile("preview-", ".html");
                Files.writeString(body, PAGE);
                return new FetchResult(false, body, null, null);
            } catch (final IOException exception) {
                throw new UncheckedIOException(exception);
            }
        };
    }

}
