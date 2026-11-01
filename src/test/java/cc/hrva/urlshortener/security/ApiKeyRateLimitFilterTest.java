package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.ratelimit.RateLimitWindow;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ApiKeyRateLimitFilterTest {

    private static final String KEY = "0b7c4f2e-6a1d-4e8b-9f3a-2c5d7e9f1a3b";
    private static final long KEY_ID = 42L;
    private static final Duration HOUR = Duration.ofHours(1);

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private RateLimiter rateLimiter;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
    private SettingsService settingsService;
    private ApiKeyRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        settingsService = SettingsServiceFixtures.defaults(new AppProperties());
        filter = new ApiKeyRateLimitFilter(apiKeyRepository, rateLimiter, settingsService, clock);
    }

    @Test
    void anAllowedRequestGoesOnWithItsLimitHeaders() throws Exception {
        knownKey();
        when(rateLimiter.incrementWindow("api-key:" + KEY_ID, HOUR)).thenReturn(new RateLimitWindow(5, Duration.ofMillis(1_799_001)));
        final var chain = new MockFilterChain();
        final var response = new MockHttpServletResponse();

        filter.doFilter(apiKeyRequest("/api/v1/urls"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("600");
        assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("595");
        assertThat(response.getHeader("RateLimit-Reset")).isEqualTo("1800");
        assertThat(response.getHeader("Retry-After")).isNull();
        verify(apiKeyRepository).recordRequest(eq(KEY_ID), any(LocalDateTime.class));
    }

    @Test
    void anAllowedRequestIsCountedOnceAsAUseOfTheKey() throws Exception {
        knownKey();
        when(rateLimiter.incrementWindow(anyString(), any())).thenReturn(new RateLimitWindow(1, HOUR));
        final var counted = new ArrayList<LocalDateTime>();
        when(apiKeyRepository.recordRequest(eq(KEY_ID), any(LocalDateTime.class))).thenAnswer(invocation -> {
            counted.add(invocation.getArgument(1));
            return 1;
        });

        filter.doFilter(apiKeyRequest("/api/v1/urls/abc/peek"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(counted).containsExactly(LocalDateTime.of(2026, 10, 30, 10, 0));
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void aKeyThatIsNoLongerValidIsNotCountedAndLeftToTheEndpoint() throws Exception {
        knownKey();
        when(rateLimiter.incrementWindow(anyString(), any())).thenReturn(new RateLimitWindow(1, HOUR));
        when(apiKeyRepository.recordRequest(eq(KEY_ID), any(LocalDateTime.class))).thenReturn(0);
        final var chain = new MockFilterChain();

        filter.doFilter(apiKeyRequest("/api/v1/urls"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void theLastAllowedRequestHasNoneRemaining() throws Exception {
        knownKey();
        when(rateLimiter.incrementWindow(anyString(), any())).thenReturn(new RateLimitWindow(600, Duration.ofMinutes(10)));
        final var chain = new MockFilterChain();
        final var response = new MockHttpServletResponse();

        filter.doFilter(apiKeyRequest("/api/v1/urls/export"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void aRequestOverTheLimitIsRefusedWithTheMinutesToWait() throws Exception {
        knownKey();
        when(rateLimiter.incrementWindow(anyString(), any())).thenReturn(new RateLimitWindow(601, Duration.ofSeconds(1_501)));
        final var chain = new MockFilterChain();
        final var response = new MockHttpServletResponse();

        filter.doFilter(apiKeyRequest("/api/v1/urls"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).isEqualTo("""
                {"type":"about:blank","title":"Too Many Requests","status":429,\
                "detail":"This API key made too many requests. Try again in 26 minutes.","instance":"/api/v1/urls"}""");
        assertThat(response.getHeader("Retry-After")).isEqualTo("1501");
        assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("600");
        assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("RateLimit-Reset")).isEqualTo("1501");
        verify(apiKeyRepository, never()).recordRequest(any(), any());
    }

    @Test
    void underAMinuteToWaitSaysOneMinute() throws Exception {
        knownKey();
        when(rateLimiter.incrementWindow(anyString(), any())).thenReturn(new RateLimitWindow(700, Duration.ofMillis(200)));
        final var response = new MockHttpServletResponse();

        filter.doFilter(apiKeyRequest("/api/v1/urls"), response, new MockFilterChain());

        assertThat(response.getContentAsString()).contains("Try again in 1 minute.\"");
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getHeader("RateLimit-Reset")).isEqualTo("1");
    }

    @Test
    void exactlyTwoMinutesToWaitSaysTwoMinutes() throws Exception {
        knownKey();
        when(rateLimiter.incrementWindow(anyString(), any())).thenReturn(new RateLimitWindow(700, Duration.ofSeconds(120)));
        final var response = new MockHttpServletResponse();

        filter.doFilter(apiKeyRequest("/api/v1/urls"), response, new MockFilterChain());

        assertThat(response.getContentAsString()).contains("Try again in 2 minutes.\"");
    }

    @Test
    void theLimitFollowsTheSetting() throws Exception {
        knownKey();
        SettingsServiceFixtures.change(settingsService, SettingKey.API_KEY_REQUESTS_PER_HOUR, 10);
        when(rateLimiter.incrementWindow(anyString(), any())).thenReturn(new RateLimitWindow(11, Duration.ofMinutes(30)));
        final var response = new MockHttpServletResponse();

        filter.doFilter(apiKeyRequest("/api/v1/urls"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("10");
    }

    @Test
    void anUnknownKeyIsLeftToTheEndpointWithoutCounting() throws Exception {
        when(apiKeyRepository.findIdByKeyHash(ApiKeyTokens.hash("not-a-key"))).thenReturn(Optional.empty());
        final var request = new MockHttpServletRequest("POST", "/api/v1/urls");
        request.addHeader("X-Api-Key", "not-a-key");
        final var chain = new MockFilterChain();
        final var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getHeader("RateLimit-Limit")).isNull();
        verifyNoInteractions(rateLimiter);
        verify(apiKeyRepository, never()).recordRequest(any(), any());
    }

    @Test
    void aRequestWithoutAKeyIsNotCounted() throws Exception {
        final var chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/urls"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(apiKeyRepository, rateLimiter);
    }

    @Test
    void aKeySentOutsideTheLinkApiIsNotCounted() throws Exception {
        final var chain = new MockFilterChain();

        filter.doFilter(apiKeyRequest("/api/v1/users/me"), new MockHttpServletResponse(), chain);
        filter.doFilter(apiKeyRequest("/api/v1/urlsx"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(apiKeyRepository, rateLimiter);
    }

    @Test
    void whenTheLimiterIsDownTheRequestGoesOnWithoutHeadersAndOneWarningAMinute(final CapturedOutput output) throws Exception {
        knownKey();
        when(rateLimiter.incrementWindow(anyString(), any()))
                .thenThrow(new RateLimiterUnavailableException(new RedisConnectionFailureException("down")));

        for (var request = 0; request < 3; request++) {
            final var chain = new MockFilterChain();
            final var response = new MockHttpServletResponse();
            filter.doFilter(apiKeyRequest("/api/v1/urls"), response, chain);
            assertThat(chain.getRequest()).isNotNull();
            assertThat(response.getHeader("RateLimit-Limit")).isNull();
        }
        clock.advance(Duration.ofSeconds(59));
        filter.doFilter(apiKeyRequest("/api/v1/urls"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(warnings(output)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        filter.doFilter(apiKeyRequest("/api/v1/urls"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(warnings(output)).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain(KEY);
        verify(apiKeyRepository, times(5)).recordRequest(eq(KEY_ID), any(LocalDateTime.class));
    }

    private static long warnings(final CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains("WARN") && line.contains("API key rate limit unavailable")).count();
    }

    private void knownKey() {
        when(apiKeyRepository.findIdByKeyHash(ApiKeyTokens.hash(KEY))).thenReturn(Optional.of(KEY_ID));
    }

    private static MockHttpServletRequest apiKeyRequest(final String path) {
        final var request = new MockHttpServletRequest("POST", path);
        request.addHeader("X-Api-Key", KEY);
        return request;
    }

    @Test
    void theKeyIsCountedUnderItsIdNotItsValue() throws Exception {
        knownKey();
        when(rateLimiter.incrementWindow(anyString(), any())).thenReturn(new RateLimitWindow(1, HOUR));

        filter.doFilter(apiKeyRequest("/api/v1/urls"), new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimiter).incrementWindow("api-key:42", HOUR);
    }

}
