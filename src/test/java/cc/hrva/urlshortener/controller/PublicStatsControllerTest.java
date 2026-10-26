package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.BlockedLinks;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.Blocklist;
import cc.hrva.urlshortener.dto.stats.PublicStatsResponse.DailyClicks;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.PublicStatsService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PublicStatsControllerTest {

    private static final String PUBLIC_STATS = "/api/v1/public/stats";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String TOO_MANY = "You've opened these numbers a lot in a short time. Try again in an hour.";
    private static final LocalDate FROM = LocalDate.of(2026, 9, 26);
    private static final LocalDate TO = LocalDate.of(2026, 10, 25);
    private static final PublicStatsResponse STATS = new PublicStatsResponse(FROM, TO, 700, 40, 1200,
            List.of(new DailyClicks(FROM, 30)), new BlockedLinks(12, 3), 4, null,
            List.of(new Blocklist("HaGeZi TIF medium", BlocklistAction.BLOCK, 120_000)), LocalDateTime.of(2026, 10, 26, 10, 0));

    @Mock
    private PublicStatsService publicStatsService;

    @Mock
    private ClientIpResolver clientIpResolver;

    private final Map<String, Integer> requests = new HashMap<>();

    private final List<Duration> windows = new ArrayList<>();

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(clientIpResolver.getClientIp(any())).thenReturn(CLIENT_IP);
    }

    private MockMvc mockMvcWith(final RateLimiter rateLimiter) {
        return MockMvcBuilders.standaloneSetup(new PublicStatsController(publicStatsService, rateLimiter, clientIpResolver))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockMvc countingMockMvc() {
        return mockMvcWith((key, window) -> {
            keys.add(key);
            windows.add(window);
            return requests.merge(key, 1, Integer::sum);
        });
    }

    @Test
    void answersWithTheNumbersAndAPublicCacheHeader() throws Exception {
        when(publicStatsService.stats()).thenReturn(STATS);

        countingMockMvc().perform(get(PUBLIC_STATS))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=600")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(jsonPath("$.from").value("2026-09-26"))
                .andExpect(jsonPath("$.to").value("2026-10-25"))
                .andExpect(jsonPath("$.activeLinks").value(700))
                .andExpect(jsonPath("$.daily[0].clicks").value(30))
                .andExpect(jsonPath("$.blocked.allTime").value(12))
                .andExpect(jsonPath("$.averageReviewMinutes").isEmpty())
                .andExpect(jsonPath("$.blocklists[0].action").value("BLOCK"));
    }

    @Test
    void sixtyRequestsAnHourPerNetworkAreAllowedAndTheSixtyFirstIs429() throws Exception {
        when(publicStatsService.stats()).thenReturn(STATS);
        final var mockMvc = countingMockMvc();

        for (int request = 0; request < 60; request++) {
            mockMvc.perform(get(PUBLIC_STATS)).andExpect(status().isOk());
        }

        mockMvc.perform(get(PUBLIC_STATS))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY));
        assertThat(windows).containsOnly(Duration.ofHours(1));
        assertThat(keys).containsOnly(RateLimiter.clientKey("public-stats", CLIENT_IP));
        assertThat(keys.getFirst()).doesNotContain(CLIENT_IP);
    }

    @Test
    void theSixtyFirstRequestInAnHourIs429() throws Exception {
        mockMvcWith((key, window) -> 61).perform(get(PUBLIC_STATS))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY));

        verifyNoInteractions(publicStatsService);
    }

    @Test
    void aDownRateLimiterAllowsTheRequest(final CapturedOutput output) throws Exception {
        when(publicStatsService.stats()).thenReturn(STATS);

        mockMvcWith((key, window) -> {
            throw new RateLimiterUnavailableException(new IllegalStateException("redis.internal:6379"));
        }).perform(get(PUBLIC_STATS)).andExpect(status().isOk());

        assertThat(output).containsOnlyOnce("Public stats rate limit unavailable, allowing the request").doesNotContain("redis.internal", CLIENT_IP);
    }

    @Test
    void theEndpointIsDocumented() throws NoSuchMethodException {
        final var operation = PublicStatsController.class.getMethod("publicStats", HttpServletRequest.class)
                .getAnnotation(Operation.class);

        assertThat(operation.summary()).isEqualTo("Site-wide numbers");
        assertThat(operation.description()).isEqualTo("Aggregates for the last 30 full days. Nothing per link, user, referrer or country. "
                + "60 requests per hour per client.");
    }

}
