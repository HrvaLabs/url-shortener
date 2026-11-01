package cc.hrva.urlshortener.mcp.authorization;

import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DcrRateLimitFilterTest {

    private static final String CLIENT_IP = "203.0.113.7";
    private static final String KEY = RateLimiter.clientKey("dcr", CLIENT_IP);
    private static final Duration HOUR = Duration.ofHours(1);

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private ClientIpResolver clientIpResolver;

    private DcrRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new DcrRateLimitFilter(request -> true, rateLimiter, clientIpResolver);
        when(clientIpResolver.getClientIp(any())).thenReturn(CLIENT_IP);
    }

    @Test
    void aRegistrationOverTheLimitWaitsOnlyTheSecondsLeftInTheHour() throws Exception {
        when(rateLimiter.tryAcquire(KEY, 20, HOUR)).thenReturn(false);
        when(rateLimiter.resetsIn(KEY)).thenReturn(Optional.of(Duration.ofMillis(1_234_001)));
        final var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("POST", "/oauth2/register"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1235");
    }

    @Test
    void aRegistrationOverTheLimitWaitsTheWholeHourWhenItsResetIsUnknown() throws Exception {
        when(rateLimiter.tryAcquire(KEY, 20, HOUR)).thenReturn(false);
        final var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("POST", "/oauth2/register"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("3600");
    }

}
