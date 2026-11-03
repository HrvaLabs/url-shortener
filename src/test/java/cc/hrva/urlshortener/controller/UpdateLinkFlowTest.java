package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.audit.AdminAuditAspect;
import cc.hrva.urlshortener.audit.AuditSubjects;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.converter.CreateUrlToUrlConverter;
import cc.hrva.urlshortener.converter.UrlToPeekUrlConverter;
import cc.hrva.urlshortener.converter.UrlUpdateDtoToUrlConverter;
import cc.hrva.urlshortener.dto.ReputationResult;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.CustomDomainRequestRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.SessionToken;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.ApiKeyService;
import cc.hrva.urlshortener.service.BurstAlertService;
import cc.hrva.urlshortener.service.CampaignService;
import cc.hrva.urlshortener.service.ClickAnalyticsService;
import cc.hrva.urlshortener.service.ClickCheckService;
import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.service.DomainReputationService;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.RedirectChainService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultLinkCheckService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.service.inspection.LinkInspectionService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.HumanCheckTestConfiguration;
import cc.hrva.urlshortener.service.SignInFormCheckTestConfiguration;
import cc.hrva.urlshortener.service.linksignal.AnonymousLinkSignalsTestConfiguration;
import cc.hrva.urlshortener.service.settings.DefaultSettingsTestConfiguration;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import cc.hrva.urlshortener.validator.impl.DefaultUrlValidator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.dto.SignInMethodResponse;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.model.enums.ChangeChannel;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.LinkDestinationChangeRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.security.ApiKeyRateLimitFilter;
import cc.hrva.urlshortener.security.ApiKeyTokens;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.SignInMethodService;
import cc.hrva.urlshortener.service.impl.DefaultAccountStandingService;
import cc.hrva.urlshortener.service.impl.DefaultDestinationChangeService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import cc.hrva.urlshortener.util.ApiDates;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.OptionalLong;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.Cache;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.web.servlet.ResultActions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest(
        classes = UpdateLinkFlowTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:update-link-flow;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class UpdateLinkFlowTest {

    private static final long OWNER = 400L;
    private static final long ADMIN = 401L;
    private static final long LINK = 410L;
    private static final long OTHER_LINK = 411L;
    private static final long DOMAIN = 420L;
    private static final long KEY = 430L;
    private static final String KEY_VALUE = "hrva_EditLinkFlowKey0000000000000000000000000";
    private static final String NEW_DESTINATION = "https://example.org/new";
    private static final String CLICKS_USED_UP = "This link has used all its clicks. Edit it to raise or remove its visit limit first.";
    private static final String OWNER_EMAIL = "owner@hrva.cc";
    private static final String ADMIN_EMAIL = "admin@hrva.cc";
    private static final long ROLE_USER = 1L;
    private static final long ROLE_ADMIN = 2L;
    private static final String OLD_DESTINATION = "https://example.com/old";
    private static final String OLD_FINAL_URL = "https://old.example/landing";
    private static final String BLOCKED_WHILE_EDITING = "This link was blocked while you were editing it. Reload and try again.";
    private static final RSAKey SIGNING_KEY = generateSigningKey();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LinkDestinationChangeRepository linkDestinationChangeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private LinkInspectionService linkInspectionService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private BlocklistStore blocklistStore;

    @MockitoBean
    private DomainAgeService domainAgeService;

    @MockitoBean
    private DomainReputationService domainReputationService;

    @MockitoBean
    private SafeBrowsingService safeBrowsingService;

    @MockitoBean
    private SuspiciousUrlService suspiciousUrlService;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private ApiKeyValidator apiKeyValidator;

    @MockitoBean
    private ClickAnalyticsService clickAnalyticsService;

    @MockitoBean
    private ClickCheckService clickCheckService;

    @MockitoBean
    private ReviewQueueService reviewQueueService;

    @MockitoBean
    private ScammerSignalService scammerSignalService;

    @MockitoBean
    private RedirectChainService redirectChainService;

    @MockitoBean
    private BurstAlertService burstAlertService;

    @MockitoBean
    private CampaignService campaignService;

    @MockitoBean
    private QrCodeService qrCodeService;

    @MockitoBean
    private LinkPreviewService linkPreviewService;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private CustomDomainRegistry customDomainRegistry;

    @MockitoBean
    private SignInMethodService signInMethodService;

    @MockitoBean
    private TaskScheduler taskScheduler;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        account(OWNER, OWNER_EMAIL, ROLE_USER);
        account(ADMIN, ADMIN_EMAIL, ROLE_USER, ROLE_ADMIN);
        jdbcTemplate.update("update user_account set two_step_enabled = true where id = ?", ADMIN);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        jdbcTemplate.update("""
                insert into url (id, short_url, long_url, owner_id, active, visits, final_url, chain_checked_at)
                values (?, 'edit1', ?, ?, true, 0, ?, current_timestamp)""",
                LINK, OLD_DESTINATION, OWNER, OLD_FINAL_URL);
        signedIn("owner-token", OWNER_EMAIL, "ROLE_USER");
        signedIn("admin-token", ADMIN_EMAIL, "ROLE_ADMIN");
        when(userService.getUserFromToken()).thenAnswer(invocation -> signedInAccount());
        when(domainReputationService.lookup(anyString())).thenReturn(ReputationResult.none());
    }

    @AfterEach
    void tearDown() {
        SettingsServiceFixtures.turnOn(settingsService, SettingKey.DESTINATION_CHANGES);
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from api_key");
        jdbcTemplate.update("delete from link_review");
        jdbcTemplate.update("delete from link_destination_change");
        jdbcTemplate.update("delete from url_tags where url_id = ?", LINK);
        jdbcTemplate.update("delete from url where owner_id = ? or id in (?, ?)", OWNER, LINK, OTHER_LINK);
        jdbcTemplate.update("delete from custom_domain where id = ?", DOMAIN);
        jdbcTemplate.update("delete from user_account_authorities where user_id in (?, ?)", OWNER, ADMIN);
        jdbcTemplate.update("delete from user_account where id in (?, ?)", OWNER, ADMIN);
    }

    @Test
    void anAdminsNewDestinationStartsWithoutTheOldDestinationsRedirectDetails() throws Exception {
        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.org/new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.org/new"));

        assertThat(jdbcTemplate.queryForMap("select long_url, final_url, chain_checked_at from url where id = ?", LINK))
                .containsEntry("LONG_URL", "https://example.org/new")
                .containsEntry("FINAL_URL", null)
                .containsEntry("CHAIN_CHECKED_AT", null);
    }

    @Test
    void aBlockMadeRightAfterAnAdminsNewDestinationIsSavedIsKept() throws Exception {
        doAnswer(invocation -> {
            blockMeanwhile();
            return null;
        }).when(redirectChainService).checkLater(LINK);

        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.org/new\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForMap("select long_url, final_url, active, block_reason, block_detail from url where id = ?", LINK))
                .containsEntry("LONG_URL", "https://example.org/new")
                .containsEntry("FINAL_URL", null)
                .containsEntry("ACTIVE", false)
                .containsEntry("BLOCK_REASON", "ADMIN")
                .containsEntry("BLOCK_DETAIL", "blocked meanwhile");
    }

    @Test
    void aBlockMadeWhileAnAdminsNewDestinationIsCheckedRefusesTheEditAndIsKept() throws Exception {
        when(safeBrowsingService.checkUrlsForThreats(anyString())).thenAnswer(invocation -> {
            blockMeanwhile();
            return List.of();
        });

        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.org/new\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(BLOCKED_WHILE_EDITING));

        assertThat(jdbcTemplate.queryForMap("select long_url, active, block_reason, block_detail from url where id = ?", LINK))
                .containsEntry("LONG_URL", OLD_DESTINATION)
                .containsEntry("ACTIVE", false)
                .containsEntry("BLOCK_REASON", "ADMIN")
                .containsEntry("BLOCK_DETAIL", "blocked meanwhile");
    }

    @Test
    void anOwnerWhoIsRefusedANewDestinationKeepsTheOldOne() throws Exception {
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.net/elsewhere\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You can't change where this link points yet. Turn on two-step sign-in. Verify your email."))
                .andExpect(jsonPath("$.missing[*].code").value(org.hamcrest.Matchers.contains("TWO_STEP_SIGN_IN", "VERIFIED_EMAIL")))
                .andExpect(jsonPath("$.missing[0].text").value("Turn on two-step sign-in"));

        assertThat(jdbcTemplate.queryForObject("select long_url from url where id = ?", String.class, LINK)).isEqualTo(OLD_DESTINATION);
        assertThat(jdbcTemplate.queryForObject("select count(*) from link_destination_change", Long.class)).isZero();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any());
    }

    @Test
    void aRefusedExpirationDateIsNotSaved() throws Exception {
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"expirationDate\":\"2020-01-01T00:00:00\",\"visitLimit\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Expiration date can't be in the past"));

        assertThat(jdbcTemplate.queryForMap("select expiration_date, visit_limit from url where id = ?", LINK))
                .containsEntry("EXPIRATION_DATE", null)
                .containsEntry("VISIT_LIMIT", null);
    }

    @Test
    void aBlockMadeWhileAnOwnersEditIsCheckedRefusesTheEditAndIsKept() throws Exception {
        doAnswer(invocation -> signedInAccount())
                .doAnswer(invocation -> {
                    blockMeanwhile();
                    return signedInAccount();
                })
                .doAnswer(invocation -> signedInAccount())
                .when(userService).getUserFromToken();

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"visitLimit\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(BLOCKED_WHILE_EDITING));

        assertThat(jdbcTemplate.queryForMap("select visit_limit, active, block_reason, block_detail from url where id = ?", LINK))
                .containsEntry("VISIT_LIMIT", null)
                .containsEntry("ACTIVE", false)
                .containsEntry("BLOCK_REASON", "ADMIN")
                .containsEntry("BLOCK_DETAIL", "blocked meanwhile");
    }

    @Test
    void anAdminIsShownTheVerdictOnABlockedDestinationAndNothingIsSaved() throws Exception {
        final var before = jdbcTemplate.queryForMap("select * from url where id = ?", LINK);

        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://njuskaio.cam/a/MAUB3\",\"visitLimit\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("The link checks would block this destination: ")))
                .andExpect(jsonPath("$.verdict.outcome").value("BLOCK"))
                .andExpect(jsonPath("$.verdict.reasons").isNotEmpty());

        assertThat(jdbcTemplate.queryForMap("select * from url where id = ?", LINK)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("select count(*) from link_destination_change", Long.class)).isZero();
    }

    @Test
    void anAdminWhoConfirmsABlockedDestinationSavesItMarkedAsForced() throws Exception {
        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://njuskaio.cam/a/MAUB3\",\"force\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://njuskaio.cam/a/MAUB3"));

        assertThat(jdbcTemplate.queryForMap("select changed_by, forced from link_destination_change"))
                .containsEntry("CHANGED_BY", ADMIN_EMAIL)
                .containsEntry("FORCED", true);
        assertThat(jdbcTemplate.queryForObject("select details from audit_log where action = 'CHANGE_LINK_DESTINATION'", String.class))
                .endsWith(", via: WEB, forced: true");
        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void anAdminsUnsafeDestinationNeedsAConfirmationToo() throws Exception {
        when(safeBrowsingService.checkUrlsForThreats(NEW_DESTINATION)).thenReturn(List.of("MALWARE"));

        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The link checks would block this destination: Google Safe Browsing: MALWARE."))
                .andExpect(jsonPath("$.verdict.reasons[0]").value("Google Safe Browsing: MALWARE"));
    }

    @Test
    void anAdminsFlaggedDestinationNeedsAConfirmationAndIsThenNotQueued() throws Exception {
        when(suspiciousUrlService.findReasons(NEW_DESTINATION)).thenReturn(List.of("Young domain"));

        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The link checks would send this destination to review: Young domain."))
                .andExpect(jsonPath("$.verdict.outcome").value("FLAG"));
        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\",\"force\":true}"))
                .andExpect(status().isOk());

        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void anAdminEditsEveryFieldOfALinkWhoseOwnerIsNotInGoodStanding() throws Exception {
        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\",\"visitLimit\":7,"
                        + "\"expirationDate\":\"2099-01-01T00:00:00Z\",\"password\":\"s3cret\",\"tags\":[\"fixed\"],\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value(NEW_DESTINATION))
                .andExpect(jsonPath("$.visitLimit").value(7))
                .andExpect(jsonPath("$.passwordProtected").value(true))
                .andExpect(jsonPath("$.active").value(false));

        assertThat(jdbcTemplate.queryForList("select tag from url_tags where url_id = ?", String.class, LINK)).containsExactly("fixed");
        assertThat(jdbcTemplate.queryForObject("select changed_by from link_destination_change", String.class)).isEqualTo(ADMIN_EMAIL);
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any());
    }

    @Test
    void neverAllowedStopsAnOwnerInGoodStandingOnEveryHost() throws Exception {
        goodStanding();
        override("NEVER")
                .andExpect(jsonPath("$.override").value("NEVER"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.inGoodStanding").value(true));

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("An admin turned off destination changes for your account."))
                .andExpect(jsonPath("$.missing[0].code").value("ACCOUNT_TURNED_OFF"));
        onCustomDomain("ACTIVE");
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.missing[0].code").value("ACCOUNT_TURNED_OFF"));
        mockMvc.perform(get("/api/v1/urls/{id}/destination-change", LINK).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer owner-token"))
                .andExpect(jsonPath("$.missing[0].text").value("An admin turned off destination changes for your account"));

        assertThat(jdbcTemplate.queryForMap("select performed_by, target_identifier, details from audit_log where action = 'SET_DESTINATION_CHANGE_OVERRIDE'"))
                .containsEntry("PERFORMED_BY", ADMIN_EMAIL)
                .containsEntry("TARGET_IDENTIFIER", String.valueOf(OWNER))
                .containsEntry("DETAILS", "from: AUTOMATIC, to: NEVER");
    }

    @Test
    void alwaysAllowedLetsAnOwnerNotInGoodStandingChangeTheDestination() throws Exception {
        override("ALWAYS")
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.inGoodStanding").value(false))
                .andExpect(jsonPath("$.missing[*].code").value(org.hamcrest.Matchers.contains("TWO_STEP_SIGN_IN", "VERIFIED_EMAIL")));

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isOk());
        verify(rateLimiter).tryAcquire("destination-change:" + LINK, 5, Duration.ofDays(1));

        SettingsServiceFixtures.turnOff(settingsService, SettingKey.DESTINATION_CHANGES);
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.org/again\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.missing[0].code").value("CHANGES_TURNED_ON"));
    }

    @Test
    void automaticPutsTheAccountBackUnderGoodStanding() throws Exception {
        override("ALWAYS");
        override("AUTOMATIC").andExpect(jsonPath("$.allowed").value(false));

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.missing[0].code").value("TWO_STEP_SIGN_IN"));
        assertThat(jdbcTemplate.queryForList("select details from audit_log where action = 'SET_DESTINATION_CHANGE_OVERRIDE' order by id", String.class))
                .containsExactly("from: AUTOMATIC, to: ALWAYS", "from: ALWAYS, to: AUTOMATIC");
    }

    @Test
    void onlyAdminsReadOrSetTheOverride() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/{id}/destination-changes", OWNER).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override").value("AUTOMATIC"));
        mockMvc.perform(get("/api/v1/admin/users/{id}/destination-changes", OWNER).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer owner-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/users/{id}/destination-changes", OWNER).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer owner-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"override\":\"ALWAYS\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/users/{id}/destination-changes", OWNER).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.override").value("Choose Automatic, Always allowed or Never allowed"));
        mockMvc.perform(put("/api/v1/admin/users/{id}/destination-changes", 999_999L).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"override\":\"NEVER\"}"))
                .andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject("select destination_change_override from user_account where id = ?", String.class, OWNER)).isEqualTo("AUTOMATIC");
    }

    @Test
    void anAdminsDestinationRefusedAsAShortenerLeavesTheLinkAsItWas() throws Exception {
        when(suspiciousUrlService.isShortener("https://bit.ly/abc")).thenReturn(true);
        final var before = jdbcTemplate.queryForMap("select * from url where id = ?", LINK);

        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://bit.ly/abc\",\"visitLimit\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Links to other URL shorteners can't be shortened. Use the final destination instead."));

        assertThat(jdbcTemplate.queryForMap("select * from url where id = ?", LINK)).isEqualTo(before);
    }

    @Test
    void anOwnersEditIsSaved() throws Exception {
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"visitLimit\":5,\"tags\":[\"work\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitLimit").value(5));

        assertThat(jdbcTemplate.queryForMap("select long_url, visit_limit, final_url from url where id = ?", LINK))
                .containsEntry("LONG_URL", OLD_DESTINATION)
                .containsEntry("VISIT_LIMIT", 5L)
                .containsEntry("FINAL_URL", OLD_FINAL_URL);
        assertThat(jdbcTemplate.queryForList("select tag from url_tags where url_id = ?", String.class, LINK)).containsExactly("work");
    }

    @Test
    void anOwnerInGoodStandingChangesTheDestinationAndTheChangeIsRecorded() throws Exception {
        goodStanding();

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value(NEW_DESTINATION));

        assertThat(jdbcTemplate.queryForMap("select long_url, final_url from url where id = ?", LINK))
                .containsEntry("LONG_URL", NEW_DESTINATION)
                .containsEntry("FINAL_URL", null);
        assertThat(jdbcTemplate.queryForMap("select url_id, old_long_url, new_long_url, changed_by, via from link_destination_change"))
                .containsEntry("URL_ID", LINK)
                .containsEntry("OLD_LONG_URL", OLD_DESTINATION)
                .containsEntry("NEW_LONG_URL", NEW_DESTINATION)
                .containsEntry("CHANGED_BY", OWNER_EMAIL)
                .containsEntry("VIA", "WEB");
        assertThat(jdbcTemplate.queryForMap("select performed_by, target_identifier, details from audit_log where action = 'CHANGE_LINK_DESTINATION'"))
                .containsEntry("PERFORMED_BY", OWNER_EMAIL)
                .containsEntry("TARGET_IDENTIFIER", String.valueOf(LINK))
                .containsEntry("DETAILS", "from: " + OLD_DESTINATION + ", to: " + NEW_DESTINATION + ", via: WEB");
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_log where action = 'UPDATE_URL' and performed_by = ?", Long.class, OWNER_EMAIL))
                .isOne();
        verify(rateLimiter).tryAcquire("destination-change:" + LINK, 5, Duration.ofDays(1));
        verify(redirectChainService).checkLater(LINK);
    }

    @Test
    void aDestinationChangeEvictsTheRedirectCacheAfterTheCommitAndAgainShortlyAfter() throws Exception {
        goodStanding();
        final var evictions = evictionsSeeing("long_url");

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isOk());

        assertThat(evictions).containsExactly("edit1 seeing " + NEW_DESTINATION);
        secondEviction().run();
        assertThat(evictions).containsExactly("edit1 seeing " + NEW_DESTINATION, "edit1 seeing " + NEW_DESTINATION);
    }

    @Test
    void anEditThatKeepsTheDestinationEvictsAfterTheCommitAndAgainShortlyAfter() throws Exception {
        final var evictions = evictionsSeeing("visit_limit");

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + OLD_DESTINATION + "\",\"visitLimit\":7}"))
                .andExpect(status().isOk());

        assertThat(evictions).containsExactly("edit1 seeing 7");
        secondEviction().run();
        assertThat(evictions).containsExactly("edit1 seeing 7", "edit1 seeing 7");
    }

    @Test
    void turningALinkOffEvictsAfterTheCommitAndAgainShortlyAfter() throws Exception {
        final var evictions = evictionsSeeing("active");

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + OLD_DESTINATION + "\",\"active\":false}"))
                .andExpect(status().isOk());

        assertThat(evictions).containsExactly("edit1 seeing false");
        secondEviction().run();
        assertThat(evictions).containsExactly("edit1 seeing false", "edit1 seeing false");
    }

    @Test
    void eachMissingRequirementIsListedOnItsOwn() throws Exception {
        goodStanding();
        jdbcTemplate.update("update user_account set two_step_enabled = false where id = ?", OWNER);
        expectOnlyMissing("TWO_STEP_SIGN_IN", "Turn on two-step sign-in");

        goodStanding();
        final var created = LocalDateTime.now().minusDays(10).withNano(0);
        jdbcTemplate.update("update user_account set create_date = ? where id = ?", created, OWNER);
        expectOnlyMissing("ACCOUNT_AGE", "Available from " + DAY.format(created.plusDays(30)))
                .andExpect(jsonPath("$.missing[0].availableFrom").value(ApiDates.utc(created.plusDays(30))));

        goodStanding();
        jdbcTemplate.update("update user_account set email_verified = false where id = ?", OWNER);
        expectOnlyMissing("VERIFIED_EMAIL", "Verify your email");

        goodStanding();
        otherLink("block_reason = 'BLOCKLIST', active = false");
        expectOnlyMissing("NO_BLOCKED_LINKS", "One of your links is blocked");

        goodStanding();
        otherLink("block_reason = 'CAMPAIGN', active = false");
        expectOnlyMissing("NO_LINKS_BEING_CHECKED", "One of your links is being checked");

        goodStanding();
        otherLink("active = true");
        jdbcTemplate.update("insert into link_review (url_id, reasons, flagged_at, system_flagged) values (?, 'Young domain', current_timestamp, true)", OTHER_LINK);
        expectOnlyMissing("NO_LINKS_BEING_CHECKED", "One of your links is being checked");

        goodStanding();
        final var blocked = LocalDateTime.now().minusDays(30);
        jdbcTemplate.update("update user_account set last_link_blocked_at = ? where id = ?", blocked, OWNER);
        expectOnlyMissing("NO_RECENT_BLOCKS", "One of your links was blocked. Available from " + DAY.format(blocked.plusDays(90)));

        assertThat(jdbcTemplate.queryForObject("select long_url from url where id = ?", String.class, LINK)).isEqualTo(OLD_DESTINATION);
    }

    @Test
    void aPublicReportAloneDoesNotPauseDestinationChangesUntilAnAdminActsOnIt() throws Exception {
        goodStanding();
        otherLink("active = true");
        jdbcTemplate.update("insert into link_review (url_id, reasons, flagged_at, system_flagged) values (?, 'Reported: phishing', current_timestamp, false)",
                OTHER_LINK);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isOk());

        jdbcTemplate.update("update url set active = false, block_reason = 'ADMIN' where id = ?", OTHER_LINK);
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.org/after-the-block\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.missing[0].code").value("NO_BLOCKED_LINKS"));
    }

    @Test
    void aPublicReportOnACustomDomainLinkDoesNotPauseItEither() throws Exception {
        onCustomDomain("ACTIVE");
        jdbcTemplate.update("insert into link_review (url_id, reasons, flagged_at, system_flagged) values (?, 'Reported: phishing', current_timestamp, false)", LINK);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isOk());

        verify(reviewQueueService, never()).resolveOpen(any(), any(), any());
    }

    @Test
    void anAccountThatSignsInWithGoogleNeedsNoVerifiedEmail() throws Exception {
        goodStanding();
        jdbcTemplate.update("update user_account set email_verified = false where id = ?", OWNER);
        when(signInMethodService.methodsOf(OWNER)).thenReturn(List.of(new SignInMethodResponse(SignInMethod.GOOGLE, null, null, null)));

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aGoogleSignInThatWasTurnedOffDoesNotCountAsAVerifiedEmail() throws Exception {
        goodStanding();
        jdbcTemplate.update("update user_account set email_verified = false where id = ?", OWNER);
        when(signInMethodService.methodsOf(OWNER)).thenReturn(List.of(new SignInMethodResponse(SignInMethod.GOOGLE, null, null, LocalDateTime.now())));

        expectOnlyMissing("VERIFIED_EMAIL", "Verify your email");
    }

    @Test
    void aBlockMoreThanNinetyDaysAgoNoLongerCounts() throws Exception {
        goodStanding();
        jdbcTemplate.update("update user_account set last_link_blocked_at = ? where id = ?", LocalDateTime.now().minusDays(91), OWNER);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aLinkOnTheOwnersCustomDomainChangesWithoutGoodStanding() throws Exception {
        onCustomDomain("ACTIVE");

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value(NEW_DESTINATION));

        verify(rateLimiter).tryAcquire("destination-change:" + LINK, 5, Duration.ofDays(1));
        assertThat(jdbcTemplate.queryForObject("select count(*) from link_destination_change", Long.class)).isOne();
    }

    @Test
    void aLinkOnACustomDomainThatIsOffOrBeingCheckedKeepsItsDestination() throws Exception {
        onCustomDomain("DISABLED");
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You can't change where this link points yet. This link's domain isn't active."))
                .andExpect(jsonPath("$.missing[0].code").value("DOMAIN_ACTIVE"));

        jdbcTemplate.update("update custom_domain set status = 'ACTIVE' where id = ?", DOMAIN);
        jdbcTemplate.update("insert into link_review (url_id, reasons, flagged_at, system_flagged) values (?, 'Young domain', current_timestamp, true)", LINK);
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.missing[0].code").value("LINK_NOT_BEING_CHECKED"));

        jdbcTemplate.update("delete from link_review");
        jdbcTemplate.update("update url set block_reason = 'ADMIN', active = false where id = ?", LINK);
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.missing[0].code").value("LINK_NOT_BLOCKED"));

        assertThat(jdbcTemplate.queryForObject("select long_url from url where id = ?", String.class, LINK)).isEqualTo(OLD_DESTINATION);
    }

    @Test
    void aLinkMadeWithoutAnAccountIsChangedOnlyByAnAdmin() throws Exception {
        jdbcTemplate.update("update url set owner_id = null where id = ?", LINK);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You don't have authorization for this action"));
        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("select changed_by from link_destination_change", String.class)).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void theSiteSwitchStopsOwnersButNotAdmins() throws Exception {
        goodStanding();
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.DESTINATION_CHANGES);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Changing where links point is turned off right now."))
                .andExpect(jsonPath("$.missing[0].code").value("CHANGES_TURNED_ON"));
        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isOk());

        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any());
    }

    @Test
    void theSixthChangeInADayIsRefusedWithTheExactWait() throws Exception {
        goodStanding();
        when(rateLimiter.tryAcquire("destination-change:" + LINK, 5, Duration.ofDays(1))).thenReturn(false);
        when(rateLimiter.resetsIn("destination-change:" + LINK)).thenReturn(Optional.of(Duration.ofSeconds(11_521)));

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "11521"))
                .andExpect(jsonPath("$.detail").value("This link's destination can change 5 times a day. Try again in 4 hours."));

        assertThat(jdbcTemplate.queryForObject("select long_url from url where id = ?", String.class, LINK)).isEqualTo(OLD_DESTINATION);
    }

    @Test
    void destinationChangesWaitWhileTheirCounterIsDown() throws Exception {
        goodStanding();
        when(rateLimiter.tryAcquire("destination-change:" + LINK, 5, Duration.ofDays(1))).thenThrow(new RateLimiterUnavailableException(new RuntimeException()));

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.detail").value("Changing where a link points isn't available right now. Try again in a minute."));

        assertThat(jdbcTemplate.queryForObject("select long_url from url where id = ?", String.class, LINK)).isEqualTo(OLD_DESTINATION);
    }

    @Test
    void anOwnersBlockedDestinationIsRefusedLikeANewLink() throws Exception {
        goodStanding();

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://njuskaio.cam/a/MAUB3\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("This destination is on a blocklist and can't be shortened. If you think that's a mistake, contact us."));

        assertThat(jdbcTemplate.queryForObject("select long_url from url where id = ?", String.class, LINK)).isEqualTo(OLD_DESTINATION);
        assertThat(jdbcTemplate.queryForObject("select count(*) from link_destination_change", Long.class)).isZero();
        verify(rateLimiter, never()).tryAcquire(eq("destination-change:" + LINK), anyInt(), any());
    }

    @Test
    void aNewDestinationSentWithAPastExpiryIsNotCounted() throws Exception {
        goodStanding();

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\",\"expirationDate\":\"2020-01-01T00:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Expiration date can't be in the past"));

        assertThat(jdbcTemplate.queryForObject("select long_url from url where id = ?", String.class, LINK)).isEqualTo(OLD_DESTINATION);
        verify(rateLimiter, never()).tryAcquire(eq("destination-change:" + LINK), anyInt(), any());
    }

    @Test
    void aNewDestinationWhoseTurnOnIsRefusedIsNotCounted() throws Exception {
        goodStanding();
        jdbcTemplate.update("update url set active = false, visit_limit = 2, opens = 2 where id = ?", LINK);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\",\"visitLimit\":2,\"active\":true}"))
                .andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject("select long_url from url where id = ?", String.class, LINK)).isEqualTo(OLD_DESTINATION);
        verify(rateLimiter, never()).tryAcquire(eq("destination-change:" + LINK), anyInt(), any());
    }

    @Test
    void anOwnersUnsafeDestinationIsRefusedLikeANewLink() throws Exception {
        goodStanding();
        when(safeBrowsingService.checkUrlsForThreats(NEW_DESTINATION)).thenReturn(List.of("MALWARE"));

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("This URL was flagged as unsafe and cannot be shortened"));

        assertThat(jdbcTemplate.queryForObject("select long_url from url where id = ?", String.class, LINK)).isEqualTo(OLD_DESTINATION);
        verify(rateLimiter, never()).tryAcquire(eq("destination-change:" + LINK), anyInt(), any());
    }

    @Test
    void anExpiredLinkGetsANewExpiryAndIsTurnedBackOnInOneSave() throws Exception {
        jdbcTemplate.update("update url set active = false, expiration_date = ? where id = ?", LocalDateTime.now().minusDays(2), LINK);
        final var later = LocalDateTime.now().plusDays(30).withNano(0);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"expirationDate\":\"" + ApiDates.utc(later) + "\",\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(jdbcTemplate.queryForMap("select active, expiration_date from url where id = ?", LINK))
                .containsEntry("ACTIVE", true)
                .containsEntry("EXPIRATION_DATE", Timestamp.valueOf(later));
        verify(redirectChainService).checkLater(LINK);
    }

    @Test
    void anExpiredLinkKeepsItsPastExpiryOnAnEditThatLeavesItOff() throws Exception {
        final var expired = LocalDateTime.now().minusDays(2).withNano(0);
        jdbcTemplate.update("update url set active = false, expiration_date = ? where id = ?", expired, LINK);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"expirationDate\":\"" + ApiDates.utc(expired) + "\",\"tags\":[\"old\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"expirationDate\":\"" + ApiDates.utc(expired) + "\",\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Expiration date can't be in the past"));
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"expirationDate\":\"" + ApiDates.utc(expired.minusDays(1)) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Expiration date can't be in the past"));

        assertThat(jdbcTemplate.queryForList("select tag from url_tags where url_id = ?", String.class, LINK)).containsExactly("old");
        assertThat(jdbcTemplate.queryForObject("select active from url where id = ?", Boolean.class, LINK)).isFalse();
    }

    @Test
    void aUsedUpLinkIsTurnedBackOnOnlyWithClicksLeft() throws Exception {
        jdbcTemplate.update("update url set active = false, visit_limit = 5, opens = 5 where id = ?", LINK);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"visitLimit\":5,\"active\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(CLICKS_USED_UP));
        assertThat(jdbcTemplate.queryForObject("select active from url where id = ?", Boolean.class, LINK)).isFalse();

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"visitLimit\":10,\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        assertThat(jdbcTemplate.queryForMap("select active, visit_limit from url where id = ?", LINK))
                .containsEntry("ACTIVE", true)
                .containsEntry("VISIT_LIMIT", 10L);
        verify(redirectChainService).checkLater(LINK);
    }

    @Test
    void turningALinkBackOnRunsTheChecksOfANewLink() throws Exception {
        jdbcTemplate.update("update url set active = false, long_url = 'https://njuskaio.cam/a/MAUB3' where id = ?", LINK);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("This destination is on a blocklist and can't be shortened. If you think that's a mistake, contact us."));

        assertThat(jdbcTemplate.queryForObject("select active from url where id = ?", Boolean.class, LINK)).isFalse();
    }

    @Test
    void aFlaggedDestinationIsTurnedBackOnAndQueuedLikeANewLink() throws Exception {
        jdbcTemplate.update("update url set active = false where id = ?", LINK);
        when(suspiciousUrlService.findReasons(OLD_DESTINATION)).thenReturn(List.of("Young domain"));

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"active\":true}"))
                .andExpect(status().isOk());

        verify(reviewQueueService).flag(argThat(url -> url.getId() == LINK), eq(List.of("Young domain")));
    }

    @Test
    void aBlockedLinkIsNotTurnedBackOnBySaving() throws Exception {
        jdbcTemplate.update("update url set active = false, block_reason = 'ADMIN' where id = ?", LINK);

        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("This link was blocked as unsafe. Contact support if you think that's a mistake."));
        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("This link is blocked, so saving can't turn it back on."));
    }

    @Test
    void anApiKeyEditsItsOwnersLinkUnderTheSameRules() throws Exception {
        apiKey();

        mockMvc.perform(withKey("{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.missing[*].code").value(org.hamcrest.Matchers.contains("TWO_STEP_SIGN_IN", "VERIFIED_EMAIL")));

        goodStanding();
        mockMvc.perform(withKey("{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\",\"visitLimit\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitLimit").value(50));

        assertThat(jdbcTemplate.queryForMap("select changed_by, via from link_destination_change"))
                .containsEntry("CHANGED_BY", OWNER_EMAIL)
                .containsEntry("VIA", "API_KEY");
        assertThat(jdbcTemplate.queryForList("select performed_by from audit_log where action in ('UPDATE_URL', 'CHANGE_LINK_DESTINATION') and failed = false",
                String.class)).containsOnly(OWNER_EMAIL).hasSize(2);
        assertThat(linkDestinationChangeRepository.exportOf(userRepository.findById(OWNER).orElseThrow()))
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.shortUrl()).isEqualTo(jdbcTemplate.queryForObject("select short_url from url where id = ?", String.class, LINK));
                    assertThat(change.oldLongUrl()).isEqualTo(OLD_DESTINATION);
                    assertThat(change.newLongUrl()).isEqualTo(NEW_DESTINATION);
                    assertThat(change.via()).isEqualTo(ChangeChannel.API_KEY);
                    assertThat(change.forced()).isFalse();
                });
        assertThat(linkDestinationChangeRepository.exportOf(userRepository.findById(ADMIN).orElseThrow())).isEmpty();
    }

    @Test
    void aSignedInEditIgnoresAKeySentWithIt() throws Exception {
        apiKey();
        jdbcTemplate.update("update url set owner_id = ? where id = ?", ADMIN, LINK);

        mockMvc.perform(withKey("{\"id\":" + LINK + ",\"visitLimit\":5}").header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForList("select performed_by from audit_log where action = 'UPDATE_URL' and failed = false", String.class))
                .containsExactly(ADMIN_EMAIL);
    }

    @Test
    void anApiKeyCantEditAnotherAccountsLink() throws Exception {
        apiKey();
        jdbcTemplate.update("update url set owner_id = ? where id = ?", ADMIN, LINK);

        mockMvc.perform(withKey("{\"id\":" + LINK + ",\"visitLimit\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You don't have authorization for this action"));

        assertThat(jdbcTemplate.queryForObject("select visit_limit from url where id = ?", Long.class, LINK)).isNull();
    }

    @Test
    void anUnknownKeyLearnsNothingAboutTheLinkAndWritesNoAuditRow() throws Exception {
        when(apiKeyService.fetchApiKeyByKey("hrva_unknown")).thenThrow(new ApiKeyNotFoundException("Sent API key doesn't exist"));

        for (final var id : List.of(LINK, 999_999L)) {
            mockMvc.perform(put("/api/v1/urls/{id}", id).header(ApiKeyRateLimitFilter.API_KEY_HEADER, "hrva_unknown")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"id\":" + id + ",\"visitLimit\":5}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Sent API key doesn't exist"));
        }

        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_log", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select visit_limit from url where id = ?", Long.class, LINK)).isNull();
    }

    @Test
    void anEditWithNeitherASignInNorAKeyIsAskedToSignIn() throws Exception {
        mockMvc.perform(put("/api/v1/urls/{id}", LINK).contentType(MediaType.APPLICATION_JSON).content("{\"visitLimit\":5}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Sign in to continue."));
    }

    @Test
    void theOwnerSeesWhatIsMissingBeforeEditing() throws Exception {
        jdbcTemplate.update("update user_account set email_verified = true where id = ?", OWNER);

        mockMvc.perform(get("/api/v1/urls/{id}/destination-change", LINK).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer owner-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.missing.length()").value(1))
                .andExpect(jsonPath("$.missing[0].code").value("TWO_STEP_SIGN_IN"))
                .andExpect(jsonPath("$.missing[0].text").value("Turn on two-step sign-in"))
                .andExpect(jsonPath("$.missing[0].availableFrom").doesNotExist());

        goodStanding();
        when(rateLimiter.count("destination-change:" + LINK)).thenReturn(OptionalLong.of(5));
        when(rateLimiter.resetsIn("destination-change:" + LINK)).thenReturn(Optional.of(Duration.ofHours(2)));
        mockMvc.perform(get("/api/v1/urls/{id}/destination-change", LINK).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer owner-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.changesLeft").value(0))
                .andExpect(jsonPath("$.missing[0].code").value("DAILY_CHANGES_LEFT"));

        when(rateLimiter.count("destination-change:" + LINK)).thenReturn(OptionalLong.of(2));
        mockMvc.perform(get("/api/v1/urls/{id}/destination-change", LINK).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer owner-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.missing.length()").value(0))
                .andExpect(jsonPath("$.changesLeft").value(3));

        mockMvc.perform(get("/api/v1/urls/{id}/destination-change", LINK).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.changesLeft").doesNotExist());
    }

    @Test
    void adminsReadALinksDestinationHistoryAPageAtATime() throws Exception {
        goodStanding();
        mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}")).andExpect(status().isOk());
        mockMvc.perform(edit("admin-token", "{\"id\":" + LINK + ",\"longUrl\":\"https://example.org/admin\"}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/urls/{id}/destination-changes", LINK).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer admin-token")
                        .param("sort", "changedAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].oldLongUrl").value(OLD_DESTINATION))
                .andExpect(jsonPath("$.content[0].newLongUrl").value(NEW_DESTINATION))
                .andExpect(jsonPath("$.content[1].changedBy").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.page.totalElements").value(2));
        mockMvc.perform(get("/api/v1/admin/urls/{id}/destination-changes", LINK).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer admin-token")
                        .param("search", "/admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/v1/admin/urls/{id}/destination-changes", LINK).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer admin-token")
                        .param("sort", "oldLongUrl"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/urls/{id}/destination-changes", LINK).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer owner-token"))
                .andExpect(status().isForbidden());
    }

    private ResultActions override(final String override) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/users/{id}/destination-changes", OWNER).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"override\":\"" + override + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions expectOnlyMissing(final String code, final String text) throws Exception {
        return mockMvc.perform(edit("owner-token", "{\"id\":" + LINK + ",\"longUrl\":\"" + NEW_DESTINATION + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You can't change where this link points yet. " + text + "."))
                .andExpect(jsonPath("$.missing.length()").value(1))
                .andExpect(jsonPath("$.missing[0].code").value(code))
                .andExpect(jsonPath("$.missing[0].text").value(text));
    }

    private void goodStanding() {
        jdbcTemplate.update("delete from link_review");
        jdbcTemplate.update("delete from url where id = ?", OTHER_LINK);
        jdbcTemplate.update("update user_account set two_step_enabled = true, email_verified = true, create_date = ?, last_link_blocked_at = null where id = ?",
                LocalDateTime.now().minusDays(400), OWNER);
    }

    private void otherLink(final String state) {
        jdbcTemplate.update("insert into url (id, short_url, long_url, owner_id, active, visits) values (?, 'other1', 'https://example.com/other', ?, true, 0)",
                OTHER_LINK, OWNER);
        jdbcTemplate.update("update url set " + state + " where id = ?", OTHER_LINK);
    }

    private void onCustomDomain(final String status) {
        jdbcTemplate.update("""
                insert into custom_domain (id, owner_id, hostname, hostname_key, status, reason, created_at, updated_at)
                values (?, ?, 'go.example.com', 'go.example.com', ?, 'Menu', current_timestamp, current_timestamp)""", DOMAIN, OWNER, status);
        jdbcTemplate.update("update url set domain_id = ? where id = ?", DOMAIN, LINK);
    }

    private void apiKey() {
        jdbcTemplate.update("""
                insert into api_key (id, key_hash, key_prefix, owner_id, api_calls_used, expiration_date, active)
                values (?, ?, 'hrva_E', ?, 0, ?, true)""", KEY, ApiKeyTokens.hash(KEY_VALUE), OWNER, LocalDateTime.now().plusMonths(6));
        when(apiKeyService.fetchApiKeyByKey(KEY_VALUE)).thenAnswer(invocation -> apiKeyRepository.findApiKeyByKeyHash(ApiKeyTokens.hash(KEY_VALUE)).orElseThrow());
    }

    private MockHttpServletRequestBuilder withKey(final String body) {
        return put("/api/v1/urls/{id}", LINK)
                .header(ApiKeyRateLimitFilter.API_KEY_HEADER, KEY_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private List<String> evictionsSeeing(final String column) {
        final var evictions = new CopyOnWriteArrayList<String>();
        final var urls = mock(Cache.class);
        doAnswer(invocation -> evictions.add(invocation.getArgument(0) + " seeing " + CompletableFuture.supplyAsync(
                () -> jdbcTemplate.queryForObject("select " + column + " from url where id = ?", Object.class, LINK)).join()))
                .when(urls).evict(any());
        when(cacheManager.getCache("urls")).thenReturn(urls);
        return evictions;
    }

    private Runnable secondEviction() {
        final var later = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(later.capture(), any(Instant.class));
        return later.getValue();
    }

    private MockHttpServletRequestBuilder edit(final String token, final String body) {
        return put("/api/v1/urls/{id}", LINK)
                .header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void blockMeanwhile() {
        jdbcTemplate.update("update url set active = false, block_reason = 'ADMIN', block_detail = 'blocked meanwhile', blocked_at = current_timestamp where id = ?",
                LINK);
    }

    private void account(final long id, final String email, final long... roles) {
        jdbcTemplate.update("insert into user_account (id, email, password, create_date, email_verified) values (?, ?, 'secret-hash', ?, false)", id, email,
                LocalDateTime.now().minusDays(400));
        for (final var role : roles) {
            jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, ?)", id, role);
        }
    }

    private void signedIn(final String token, final String email, final String role) {
        when(tokenProvider.read(token))
                .thenReturn(Optional.of(new SessionToken(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))), null, null, null)));
    }

    private User signedInAccount() {
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> userRepository.findByEmail(SecurityContextHolder.getContext().getAuthentication().getName()).orElse(null));
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (final JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, CustomDomainRepository.class, CustomDomainRequestRepository.class, UserRepository.class, AuditLogRepository.class, DomainRuleRepository.class,
                            BlocklistSourceRepository.class, LinkReviewRepository.class, LinkDestinationChangeRepository.class, ApiKeyRepository.class}))
    @Import({
            DefaultSettingsTestConfiguration.class,
            AnonymousLinkSignalsTestConfiguration.class, HumanCheckTestConfiguration.class, SignInFormCheckTestConfiguration.class,
            AppConfiguration.class,
            WebConfiguration.class,
            AnyHostIsPrimary.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            ClickRequestResolver.class,
            UrlController.class,
            ShortLinks.class,
            DefaultUrlService.class,
            UrlCache.class,
            DestinationCheckRepository.class,
            DefaultUrlValidator.class,
            DefaultLinkCheckService.class,
            CreateUrlToUrlConverter.class,
            UrlToPeekUrlConverter.class,
            UrlUpdateDtoToUrlConverter.class,
            AdminAuditAspect.class,
            AuditSubjects.class,
            DefaultAuditLogService.class,
            DefaultDestinationChangeService.class,
            DefaultAccountStandingService.class,
            ClockConfiguration.class,
            AdminDestinationChangeController.class
    })
    static class TestApplication {
    }

}
