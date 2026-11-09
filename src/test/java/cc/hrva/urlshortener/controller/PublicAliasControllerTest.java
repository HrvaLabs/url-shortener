package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.alias.AliasRules;
import cc.hrva.urlshortener.service.blocklist.HostLists;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.impl.DefaultSuspiciousUrlService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class PublicAliasControllerTest {

    private static final String ALIAS_CHECK = "/api/v1/public/alias-check";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String TOO_MANY = "You've checked a lot of names in a short time. Try again later.";

    private final AppProperties appProperties = new AppProperties();
    private final ReservedAliasService reservedAliasService = mock(ReservedAliasService.class);
    private final UrlRepository urlRepository = mock(UrlRepository.class);
    private final HostLists hostLists = mock(HostLists.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final Map<String, Integer> requests = new HashMap<>();
    private final List<Duration> windows = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private DefaultSettingsService settingsService;
    private AliasRules aliasRules;

    @BeforeEach
    void setUp() {
        appProperties.setServerUrl("https://hrva.cc");
        settingsService = SettingsServiceFixtures.defaults(appProperties);
        when(clientIpResolver.getClientIp(any())).thenReturn(CLIENT_IP);
        when(reservedAliasService.isReserved(anyString())).thenAnswer(invocation -> "api".equals(invocation.getArgument(0)));
        aliasRules = new AliasRules(settingsService, reservedAliasService, new DefaultSuspiciousUrlService(appProperties, hostLists), urlRepository);
    }

    private MockMvc mockMvcWith(final RateLimiter rateLimiter) {
        return MockMvcBuilders.standaloneSetup(new PublicAliasController(aliasRules, rateLimiter, clientIpResolver))
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
    void anAvailableNameHasNoReason() throws Exception {
        countingMockMvc().perform(get(ALIAS_CHECK).param("name", "karlo-github"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("karlo-github"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.reason").isEmpty());
    }

    @Test
    void theNameIsTrimmed() throws Exception {
        countingMockMvc().perform(get(ALIAS_CHECK).param("name", "  karlo  "))
                .andExpect(jsonPath("$.name").value("karlo"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void aCaseVariantIsReportedTaken() throws Exception {
        when(urlRepository.aliasTakenIgnoringCase(isNull(), eq("Karlo-GitHub"))).thenReturn(true);

        countingMockMvc().perform(get(ALIAS_CHECK).param("name", "Karlo-GitHub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TAKEN"))
                .andExpect(jsonPath("$.reason").value("This short URL is already taken. Try another."));
    }

    @Test
    void aReservedNameCarriesItsReason() throws Exception {
        countingMockMvc().perform(get(ALIAS_CHECK).param("name", "api"))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.reason").value("This short URL is reserved. Try another."));
    }

    @Test
    void aNameThatLooksLikeAnHrvaCcPageIsReservedWithItsReason() throws Exception {
        countingMockMvc().perform(get(ALIAS_CHECK).param("name", "hrva-support"))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.reason").value("This name looks like one of hrva.cc's own pages, so it can't be used. Try another."));
    }

    @Test
    void aBrandNameIsReservedEvenForASignedInAdmin() throws Exception {
        countingMockMvc().perform(get(ALIAS_CHECK).param("name", "paypal-login")
                        .with(request -> {
                            request.setUserPrincipal(() -> "admin");
                            return request;
                        }))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.reason").value("This name contains the brand name paypal, so it can't be used on hrva.cc. Try another."));
    }

    @Test
    void aMissingNameIsInvalid() throws Exception {
        countingMockMvc().perform(get(ALIAS_CHECK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(""))
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.reason").value("Use 1 to 32 letters, numbers, hyphens or underscores."));
        countingMockMvc().perform(get(ALIAS_CHECK).param("name", "   "))
                .andExpect(jsonPath("$.status").value("INVALID"));
    }

    @Test
    void customAliasesOffIsDisabled() throws Exception {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.CUSTOM_ALIASES);

        countingMockMvc().perform(get(ALIAS_CHECK).param("name", "karlo"))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.reason").value("Custom aliases are turned off right now."));
    }

    @Test
    void theAnswerIsNeverCached() throws Exception {
        countingMockMvc().perform(get(ALIAS_CHECK).param("name", "karlo"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void theAnswerNeverDescribesTheTakenLink() throws Exception {
        when(urlRepository.aliasTakenIgnoringCase(isNull(), eq("karlo"))).thenReturn(true);

        final var body = countingMockMvc().perform(get(ALIAS_CHECK).param("name", "karlo"))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonMapper.builder().build().readTree(body).propertyNames()).containsExactlyInAnyOrder("name", "status", "reason");
    }

    @Test
    void oneHundredTwentyChecksAnHourPerNetworkAreAllowedAndTheNextIs429() throws Exception {
        final var mockMvc = countingMockMvc();

        for (int request = 0; request < 120; request++) {
            mockMvc.perform(get(ALIAS_CHECK).param("name", "karlo")).andExpect(status().isOk());
        }

        mockMvc.perform(get(ALIAS_CHECK).param("name", "karlo"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(TOO_MANY));
        assertThat(windows).containsOnly(Duration.ofHours(1));
        assertThat(keys).containsOnly(RateLimiter.clientKey("alias-check", CLIENT_IP));
        assertThat(keys.getFirst()).doesNotContain(CLIENT_IP);
    }

    @Test
    void tooManyChecksAnswer429() throws Exception {
        final var rateLimiter = new RateLimiter() {
            @Override
            public long increment(final String key, final Duration window) {
                return 121;
            }

            @Override
            public Optional<Duration> resetsIn(final String key) {
                return Optional.of(Duration.ofMillis(41_500));
            }
        };

        mockMvcWith(rateLimiter).perform(get(ALIAS_CHECK).param("name", "karlo"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"))
                .andExpect(jsonPath("$.detail").value(TOO_MANY));
        verifyNoInteractions(urlRepository);
    }

    @Test
    void aDownRateLimiterAllowsTheCheck(final CapturedOutput output) throws Exception {
        mockMvcWith((key, window) -> {
            throw new RateLimiterUnavailableException(new IllegalStateException("redis.internal:6379"));
        }).perform(get(ALIAS_CHECK).param("name", "probe-name-7")).andExpect(status().isOk());

        assertThat(output).containsOnlyOnce("Alias check rate limit unavailable, allowing the request")
                .doesNotContain("redis.internal", CLIENT_IP, "probe-name-7");
    }

    @Test
    void theEndpointIsDocumented() throws NoSuchMethodException {
        final var operation = PublicAliasController.class.getMethod("aliasCheck", String.class, HttpServletRequest.class)
                .getAnnotation(Operation.class);

        assertThat(operation.description()).contains("120 requests per hour per client");
    }

}
