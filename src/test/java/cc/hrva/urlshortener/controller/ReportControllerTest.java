package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkReportDto;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.anon.RateLimiterUnavailableException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.ReviewQueueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReportControllerTest {

    private static final String REPORTS = "/api/v1/reports";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String SHORT = "v0DB3";
    private static final String DETAILS = "asks for my bank login";
    private static final String TOO_MANY_REPORTS = "You've sent a lot of reports in a short time. Try again in an hour.";

    private MockMvc mockMvc;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private HttpServletRequest request;

    private final AppProperties appProperties = new AppProperties();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Integer> attempts = new HashMap<>();

    private final List<Duration> windows = new ArrayList<>();

    private final Url url = Url.builder().id(287L).shortUrl(SHORT).longUrl("https://findfavoritedate.life/?u=f6yp608").active(true).build();

    @BeforeEach
    void setUp() {
        appProperties.setServerUrl("https://hrva.cc");
        lenient().when(clientIpResolver.getClientIp(request)).thenReturn(CLIENT_IP);
        mockMvc = mockMvcWith((key, limit, window) -> {
            windows.add(window);
            return attempts.merge(key, 1, Integer::sum) <= limit;
        });
    }

    private MockMvc mockMvcWith(final RateLimiter rateLimiter) {
        return MockMvcBuilders.standaloneSetup(new ReportController(reviewQueueService, urlRepository, rateLimiter, clientIpResolver, request, appProperties))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ResultActions report(final String shortUrl, final String category, final String details) throws Exception {
        return mockMvc.perform(post(REPORTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LinkReportDto.builder()
                        .shortUrl(shortUrl)
                        .category(category)
                        .details(details)
                        .build())));
    }

    @Test
    void aReportOnAnActiveLinkGoesToTheReviewQueue() throws Exception {
        when(urlRepository.findByShortUrlAndActiveTrue(SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "PHISHING", DETAILS)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(reviewQueueService).report(url, ReportCategory.PHISHING, DETAILS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hrva.cc/v0DB3", "https://hrva.cc/v0DB3", "HTTP://www.HRVA.cc/v0DB3/", "hrva.cc/v0DB3?utm_source=mail", " /v0DB3 "})
    void theShortLinkCanBeGivenAsALinkOnTheShortHost(final String shortUrl) throws Exception {
        when(urlRepository.findByShortUrlAndActiveTrue(SHORT)).thenReturn(Optional.of(url));

        report(shortUrl, "SPAM", null).andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.SPAM, null);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "https://bit.ly/v0DB3", "hrva.cc", "hrva.cc/", "hrva.cc/v0DB3/stats", "v0DB3.html"})
    void anythingButAShortLinkIsRefused(final String shortUrl) throws Exception {
        report(shortUrl, "PHISHING", DETAILS)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Enter a hrva.cc short link, like hrva.cc/abc"));

        verifyNoInteractions(urlRepository, reviewQueueService);
        assertThat(attempts).isEmpty();
    }

    @Test
    void anUnknownOrInactiveLinkIsAcceptedButNothingIsWritten() throws Exception {
        when(urlRepository.findByShortUrlAndActiveTrue("gone")).thenReturn(Optional.empty());

        report("gone", "MALWARE", DETAILS)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verifyNoInteractions(reviewQueueService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"SCAM", "phishing"})
    void theCategoryMustBeOneOfTheFour(final String category) throws Exception {
        report(SHORT, category, DETAILS)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Category must be PHISHING, MALWARE, SPAM or OTHER"));

        verifyNoInteractions(urlRepository, reviewQueueService);
    }

    @Test
    void detailsOverFiveHundredCharactersAreRefused() throws Exception {
        report(SHORT, "OTHER", "a".repeat(501))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Details must be 500 characters or fewer"));

        verifyNoInteractions(urlRepository, reviewQueueService);
        assertThat(attempts).isEmpty();
    }

    @Test
    void fiveHundredCharactersOfDetailsAreCountedAfterTrimming() throws Exception {
        when(urlRepository.findByShortUrlAndActiveTrue(SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "OTHER", "  " + "a".repeat(500) + "\u0007\n ").andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.OTHER, "a".repeat(500));
    }

    @Test
    void detailsAreTrimmedAndStrippedOfControlCharacters() throws Exception {
        when(urlRepository.findByShortUrlAndActiveTrue(SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "PHISHING", "  asks for\u0000 my\u001b bank\r\nlogin\u0085 ").andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.PHISHING, DETAILS);
    }

    @Test
    void blankDetailsCountAsNoDetails() throws Exception {
        when(urlRepository.findByShortUrlAndActiveTrue(SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "SPAM", " \t\u0000 ").andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.SPAM, null);
    }

    @Test
    void theSixthReportFromOneClientWithinAnHourIsRefused() throws Exception {
        when(urlRepository.findByShortUrlAndActiveTrue(SHORT)).thenReturn(Optional.of(url));

        for (var sent = 0; sent < 5; sent++) {
            report(SHORT, "PHISHING", DETAILS).andExpect(status().isAccepted());
        }
        report(SHORT, "PHISHING", DETAILS)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY_REPORTS));

        verify(reviewQueueService, times(5)).report(url, ReportCategory.PHISHING, DETAILS);
        assertThat(windows).hasSize(6).containsOnly(Duration.ofHours(1));
    }

    @Test
    void theLimitIsKeptPerHashedClientAddress() throws Exception {
        when(urlRepository.findByShortUrlAndActiveTrue(SHORT)).thenReturn(Optional.of(url));
        for (var sent = 0; sent < 5; sent++) {
            report(SHORT, "PHISHING", DETAILS).andExpect(status().isAccepted());
        }
        when(clientIpResolver.getClientIp(request)).thenReturn("198.51.100.20");

        report(SHORT, "PHISHING", DETAILS).andExpect(status().isAccepted());

        assertThat(attempts).containsOnlyKeys(RateLimiter.clientKey("report", CLIENT_IP), RateLimiter.clientKey("report", "198.51.100.20"));
        assertThat(attempts.keySet()).noneMatch(key -> key.contains(CLIENT_IP) || key.contains("198.51.100.20"));
    }

    @Test
    void reportsOnUnknownLinksCountTowardsTheLimitSoTheLimitShowsNothingAboutLinks() throws Exception {
        for (var sent = 0; sent < 5; sent++) {
            report("gone" + sent, "SPAM", null).andExpect(status().isAccepted());
        }

        report(SHORT, "PHISHING", DETAILS).andExpect(status().isTooManyRequests());

        verify(urlRepository, never()).findByShortUrlAndActiveTrue(SHORT);
        verifyNoInteractions(reviewQueueService);
    }

    @Test
    void aReportIsStillTakenWhenTheRateLimiterIsUnavailable() throws Exception {
        mockMvc = mockMvcWith((key, limit, window) -> {
            throw new RateLimiterUnavailableException(new IllegalStateException("redis down"));
        });
        when(urlRepository.findByShortUrlAndActiveTrue(SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "PHISHING", DETAILS).andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.PHISHING, DETAILS);
    }

    @Test
    void aReportNeverChangesTheLink() throws Exception {
        when(urlRepository.findByShortUrlAndActiveTrue(SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "PHISHING", DETAILS).andExpect(status().isAccepted());

        verify(urlRepository).findByShortUrlAndActiveTrue(SHORT);
        verifyNoMoreInteractions(urlRepository);
        verify(reviewQueueService).report(any(Url.class), any(ReportCategory.class), any());
        verifyNoMoreInteractions(reviewQueueService);
        assertThat(url.isActive()).isTrue();
        assertThat(url.getBlockReason()).isNull();
        assertThat(url.getBlockDetail()).isNull();
    }

}
