package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.CreateUrlToUrlConverter;
import cc.hrva.urlshortener.converter.UrlToPeekUrlConverter;
import cc.hrva.urlshortener.converter.UrlUpdateDtoToUrlConverter;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.ApiKeyService;
import cc.hrva.urlshortener.service.BurstAlertService;
import cc.hrva.urlshortener.service.ClickAnalyticsService;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.RedirectChainService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.service.impl.DefaultClickCheckService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import cc.hrva.urlshortener.validator.UrlValidator;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UrlControllerBlocklistTest {

    private static final String DESTINATION = "https://login.jofogas.cards/item/7";
    private static final String DESTINATION_HOST = "login.jofogas.cards";
    private static final BlocklistSource TIF = BlocklistSource.builder()
            .id(1L).name("HaGeZi TIF medium").kind(SourceKind.DOMAINS).action(BlocklistAction.BLOCK).build();

    private MockMvc mockMvc;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private BlocklistSourceRepository blocklistSourceRepository;

    @Mock
    private DomainRuleRepository domainRuleRepository;

    @Mock
    private BlocklistStore blocklistStore;

    @Mock
    private ModerationService moderationService;

    @Mock
    private LinkPreviewService linkPreviewService;

    @Mock
    private QrCodeService qrCodeService;

    @Mock
    private ClickAnalyticsService clickAnalyticsService;

    @Mock
    private UserService userService;

    @Mock
    private UrlValidator urlValidator;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private ApiKeyValidator apiKeyValidator;

    @Mock
    private CreateUrlToUrlConverter createUrlToUrlConverter;

    @Mock
    private UrlUpdateDtoToUrlConverter urlUpdateDtoToUrlConverter;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SuspiciousUrlService suspiciousUrlService;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private ScammerSignalService scammerSignalService;

    @Mock
    private RedirectChainService redirectChainService;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private HttpServletRequest request;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ReservedAliasService reservedAliasService;

    @Mock
    private BurstAlertService burstAlertService;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        final var clickCheckService = new DefaultClickCheckService(blocklistSourceRepository, domainRuleRepository, blocklistStore, moderationService);
        when(blocklistSourceRepository.findByEnabledTrue()).thenReturn(List.of(TIF));
        clickCheckService.reload();
        final var urlService = new DefaultUrlService(userService, urlValidator, apiKeyService, urlRepository, appProperties, apiKeyValidator,
                clickAnalyticsService, new UrlToPeekUrlConverter(), createUrlToUrlConverter, urlUpdateDtoToUrlConverter, cacheManager,
                objectMapper, meterRegistry, passwordEncoder, suspiciousUrlService, rateLimiter, reviewQueueService, scammerSignalService, clickCheckService,
                redirectChainService, TransactionOperations.withoutTransaction(), entityManager, reservedAliasService, burstAlertService);
        mockMvc = MockMvcBuilders.standaloneSetup(new UrlController(urlService, qrCodeService, linkPreviewService, clientIpResolver,
                        new ClickRequestResolver(clientIpResolver), request, appProperties))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(urlRepository.findByShortUrlAndActiveTrue("item7")).thenReturn(Optional.of(
                Url.builder().id(10L).shortUrl("item7").longUrl(DESTINATION).visits(0L).active(true).build()));
    }

    @Test
    void theLookupOfALinkOnABlockListIsNotFoundAndRevealsNoDestination() throws Exception {
        onTheBlockList();

        final var body = mockMvc.perform(get("/api/v1/urls/item7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.longUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(DESTINATION_HOST);
        verify(moderationService).blockLink(10L, BlockReason.BLOCKLIST, "HaGeZi TIF medium", "system", "click");
        verifyNoInteractions(clickAnalyticsService);
    }

    @Test
    void aPeekAtALinkOnABlockListIsNotFoundAndRevealsNoDestination() throws Exception {
        onTheBlockList();

        final var body = mockMvc.perform(get("/api/v1/urls/item7/peek"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.longUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(DESTINATION_HOST);
        verify(moderationService).blockLink(10L, BlockReason.BLOCKLIST, "HaGeZi TIF medium", "system", "click");
    }

    @Test
    void thePreviewOfALinkOnABlockListIsNotFoundAndNeverFetchesThePage() throws Exception {
        onTheBlockList();

        final var body = mockMvc.perform(get("/api/v1/urls/item7/preview"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.url").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(DESTINATION_HOST);
        verifyNoInteractions(linkPreviewService);
        verify(moderationService).blockLink(10L, BlockReason.BLOCKLIST, "HaGeZi TIF medium", "system", "click");
    }

    @Test
    void aPeekAtALinkOnNoListStillShowsItsDestination() throws Exception {
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of());

        mockMvc.perform(get("/api/v1/urls/item7/peek"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value(DESTINATION));

        verifyNoInteractions(moderationService);
    }

    private void onTheBlockList() {
        when(blocklistStore.matchingSources(any())).thenReturn(Set.of(1L));
    }

}
