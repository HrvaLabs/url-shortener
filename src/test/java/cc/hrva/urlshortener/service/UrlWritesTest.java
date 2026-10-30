package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.CreateUrlToUrlConverter;
import cc.hrva.urlshortener.converter.UrlToPeekUrlConverter;
import cc.hrva.urlshortener.converter.UrlUpdateDtoToUrlConverter;
import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.dto.DestinationHealth;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.CampaignRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.domain.CustomDomainModeration;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.domain.DomainRoute;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultModerationService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.linksignal.AnonymousLinkSignalsTestConfiguration;
import cc.hrva.urlshortener.service.settings.DefaultSettingsTestConfiguration;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import cc.hrva.urlshortener.validator.UrlValidator;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = UrlWritesTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:url-writes;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class UrlWritesTest {

    private static final long LINK = 780L;
    private static final String ALIAS = "visited-meanwhile";
    private static final LocalDateTime EXPIRES = LocalDateTime.of(2027, 1, 1, 12, 0);
    private static final LocalDateTime NEW_EXPIRY = LocalDateTime.of(2027, 6, 1, 12, 0);
    private static final LocalDateTime VISITED_AT = LocalDateTime.of(2026, 10, 27, 11, 45);
    private static final LocalDateTime DEAD_SINCE = LocalDateTime.of(2026, 10, 25, 2, 11, 40);
    private static final long ON_HRVA = 781L;
    private static final long ON_DOMAIN = 782L;
    private static final long DOMAIN_OWNER = 783L;
    private static final ClickRequest CLICK = ClickRequest.of("203.0.113.7", "Mozilla/5.0", null, "HR");

    private final AtomicBoolean visitDone = new AtomicBoolean();

    private Runnable visitMeanwhile = () -> {};

    @Autowired
    private UrlService urlService;

    @Autowired
    private ModerationService moderationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private UrlRepository urlRepository;

    @MockitoSpyBean
    private DataSource dataSource;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UrlValidator urlValidator;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private AppProperties appProperties;

    @MockitoBean
    private ApiKeyValidator apiKeyValidator;

    @MockitoBean
    private ClickAnalyticsService clickAnalyticsService;

    @MockitoBean
    private UrlToPeekUrlConverter urlToPeekUrlConverter;

    @MockitoBean
    private CreateUrlToUrlConverter createUrlToUrlConverter;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private SuspiciousUrlService suspiciousUrlService;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private ReviewQueueService reviewQueueService;

    @MockitoBean
    private ScammerSignalService scammerSignalService;

    @MockitoBean
    private ClickCheckService clickCheckService;

    @MockitoBean
    private RedirectChainService redirectChainService;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    @MockitoBean
    private BurstAlertService burstAlertService;

    @MockitoBean
    private CampaignService campaignService;

    @MockitoBean
    private LinkCheckService linkCheckService;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private TaskScheduler taskScheduler;

    @MockitoBean
    private CustomDomainRegistry customDomainRegistry;

    @MockitoBean
    private CustomDomainModeration customDomainModeration;

    @BeforeEach
    void visitTheLinkRightAfterTheWriteTransactionReadsIt() {
        doAnswer(invocation -> {
            final var loaded = mockingDetails(urlRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            if (TransactionSynchronizationManager.isActualTransactionActive() && visitDone.compareAndSet(false, true)) {
                CompletableFuture.runAsync(visitMeanwhile).join();
            }
            return loaded;
        }).when(urlRepository).findById(LINK);
    }

    @AfterEach
    void removeTheLink() {
        jdbcTemplate.update("delete from url where id in (?, ?, ?)", LINK, ON_HRVA, ON_DOMAIN);
        jdbcTemplate.update("delete from custom_domain where owner_id = ?", DOMAIN_OWNER);
        jdbcTemplate.update("delete from user_account where id = ?", DOMAIN_OWNER);
    }

    @Test
    void eachHostRedirectsToItsOwnLinkFromTheDatabaseAndABlockEvictsOnlyThatLinksKey() {
        final var cache = new ConcurrentMapCache("urls");
        when(cacheManager.getCache("urls")).thenReturn(cache);
        when(appProperties.getFrontendUrl()).thenReturn("https://app.hrva.cc");
        when(clickCheckService.mayRedirect(anyLong(), any())).thenReturn(true);
        final var domainId = goExampleCom();
        final var route = new DomainRoute(domainId, "go.example.com", CustomDomainStatus.ACTIVE, true);
        menu(ON_HRVA, "https://example.com/a", null);
        menu(ON_DOMAIN, "https://example.com/b", domainId);

        assertThat(locationOf(urlService.redirectResultUrl("menu", CLICK))).isEqualTo("https://example.com/a");
        assertThat(urlService.redirectResultUrl(route, "menu", CLICK).getUrl()).isEqualTo("https://example.com/b");
        assertThat(((UrlResponse) cache.get("menu").get()).id()).isEqualTo(ON_HRVA);
        assertThat(((UrlResponse) cache.get(domainId + "/menu").get()).id()).isEqualTo(ON_DOMAIN);

        moderationService.blockLink(ON_DOMAIN, BlockReason.ADMIN, "phishing", "admin@hrva.cc", "admin");

        assertThat(cache.get(domainId + "/menu")).isNull();
        assertThat(cache.get("menu")).isNotNull();
        assertThat(urlService.redirectResultUrl(route, "menu", CLICK).getUrl())
                .isEqualTo("https://app.hrva.cc/unavailable?s=menu&reason=blocked&d=go.example.com");
        assertThat(locationOf(urlService.redirectResultUrl("menu", CLICK))).isEqualTo("https://example.com/a");
    }

    @Test
    void aCachedLinkRedirectsWithoutATransactionOrAConnectionWhileTheDatabaseIsDown() throws SQLException {
        final var cache = new ConcurrentMapCache("urls");
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var transactionDuringTheCheck = new AtomicBoolean();
        final var readOnlyDuringTheCheck = new AtomicBoolean();
        when(clickCheckService.mayRedirect(anyLong(), any())).thenAnswer(invocation -> {
            transactionDuringTheCheck.set(TransactionSynchronizationManager.isActualTransactionActive());
            readOnlyDuringTheCheck.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return true;
        });
        final var domainId = goExampleCom();
        final var route = new DomainRoute(domainId, "go.example.com", CustomDomainStatus.ACTIVE, true);
        menu(ON_HRVA, "https://example.com/a", null);
        menu(ON_DOMAIN, "https://example.com/b", domainId);
        assertThat(locationOf(urlService.redirectResultUrl("menu", CLICK))).isEqualTo("https://example.com/a");
        assertThat(transactionDuringTheCheck).isTrue();
        assertThat(readOnlyDuringTheCheck).isTrue();
        assertThat(urlService.redirectResultUrl(route, "menu", CLICK).getUrl()).isEqualTo("https://example.com/b");

        doThrow(new SQLException("Connection refused")).when(dataSource).getConnection();
        try {
            assertThat(locationOf(urlService.redirectResultUrl("menu", CLICK))).isEqualTo("https://example.com/a");
            assertThat(urlService.redirectResultUrl(route, "menu", CLICK).getUrl()).isEqualTo("https://example.com/b");
        } finally {
            doCallRealMethod().when(dataSource).getConnection();
        }

        assertThat(transactionDuringTheCheck).isFalse();
        verify(clickAnalyticsService, times(2)).recordClick(ON_HRVA, "menu", CLICK);
        verify(clickAnalyticsService, times(2)).recordClick(ON_DOMAIN, domainId + "/menu", CLICK);
    }

    @Test
    void anEditKeepsAClickAndTheVisitLimitStopThatLandedAfterTheReRead() {
        link(4, 5L);
        visitMeanwhile = () -> {
            assertThat(urlRepository.recordVisit(LINK, VISITED_AT)).isOne();
            assertThat(urlRepository.deactivateAtVisitLimit(LINK)).isOne();
        };

        urlService.updateUrl(UrlUpdateDto.builder().id(LINK).visitLimit(5L).expirationDate(NEW_EXPIRY).build());

        assertThat(visitDone).isTrue();
        assertThat(linkRow())
                .containsEntry("VISITS", 5L)
                .containsEntry("LAST_ACCESSED", Timestamp.valueOf(VISITED_AT))
                .containsEntry("ACTIVE", false)
                .containsEntry("EXPIRATION_DATE", Timestamp.valueOf(NEW_EXPIRY));
    }

    @Test
    void aRevokeWritesOnlyActiveSoAClickCountedAfterTheLinkWasLoadedStays() {
        link(4, null);
        visitMeanwhile = () -> assertThat(urlRepository.recordVisit(LINK, VISITED_AT)).isOne();

        urlService.revokeUrl(LINK);

        assertThat(visitDone).isTrue();
        assertThat(linkRow())
                .containsEntry("VISITS", 5L)
                .containsEntry("LAST_ACCESSED", Timestamp.valueOf(VISITED_AT))
                .containsEntry("ACTIVE", false);
    }

    @Test
    void aModerationBlockKeepsAClickCountedAfterTheLinkWasReRead() {
        link(4, null);
        visitMeanwhile = () -> assertThat(urlRepository.recordVisit(LINK, VISITED_AT)).isOne();

        moderationService.blockLink(LINK, BlockReason.ADMIN, "phishing", "admin@hrva.cc", "admin");

        assertThat(visitDone).isTrue();
        assertThat(linkRow())
                .containsEntry("VISITS", 5L)
                .containsEntry("LAST_ACCESSED", Timestamp.valueOf(VISITED_AT))
                .containsEntry("ACTIVE", false)
                .containsEntry("BLOCK_REASON", "ADMIN");
    }

    @Test
    void aDestinationEditClearsTheDeadStateTheNightlyCheckWrote() {
        link(4, null);
        markDead();
        visitMeanwhile = () -> {};
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(any())).thenReturn(LinkVerdict.clean());

        final var response = urlService.updateUrl(UrlUpdateDto.builder().id(LINK).longUrl("https://example.com/moved").build());

        assertThat(response.destinationHealth()).isNull();
        assertThat(destinationRow())
                .containsEntry("LONG_URL", "https://example.com/moved")
                .containsEntry("DESTINATION_CHECKED_AT", null)
                .containsEntry("DESTINATION_DEAD_SINCE", null)
                .containsEntry("DESTINATION_FAIL_STREAK", 0)
                .containsEntry("DESTINATION_LAST_STATUS", null);
    }

    @Test
    void anEditThatKeepsTheDestinationKeepsTheDeadState() {
        link(4, null);
        markDead();
        visitMeanwhile = () -> {};

        final var response = urlService.updateUrl(UrlUpdateDto.builder().id(LINK).visitLimit(9L).build());

        assertThat(response.destinationHealth()).isEqualTo(new DestinationHealth(DEAD_SINCE, "404"));
        assertThat(destinationRow())
                .containsEntry("DESTINATION_DEAD_SINCE", Timestamp.valueOf(DEAD_SINCE))
                .containsEntry("DESTINATION_FAIL_STREAK", 3)
                .containsEntry("DESTINATION_LAST_STATUS", "404");
    }

    private long goExampleCom() {
        jdbcTemplate.update("insert into user_account (id, email, password, active, create_date) values (?, 'domain-owner@hrva.cc', 'hash', true, ?)",
                DOMAIN_OWNER, LocalDateTime.of(2026, 9, 1, 9, 0));
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, cloudflare_delete_pending, created_at, updated_at)
                values (?, 'go.example.com', 'go.example.com', 'ACTIVE', 'Menu links for my cafe', false, ?, ?)""",
                DOMAIN_OWNER, LocalDateTime.of(2026, 10, 1, 9, 0), LocalDateTime.of(2026, 10, 1, 9, 0));
        return jdbcTemplate.queryForObject("select id from custom_domain where owner_id = ?", Long.class, DOMAIN_OWNER);
    }

    private void menu(final long id, final String longUrl, final Long domainId) {
        jdbcTemplate.update("""
                insert into url (id, long_url, short_url, visits, create_date, active, domain_id)
                values (?, ?, 'menu', 0, ?, true, ?)""", id, longUrl, LocalDateTime.of(2026, 10, 1, 9, 0), domainId);
    }

    private void markDead() {
        jdbcTemplate.update("""
                update url set destination_checked_at = ?, destination_dead_since = ?, destination_fail_streak = 3,
                               destination_last_status = '404' where id = ?""", DEAD_SINCE, DEAD_SINCE, LINK);
    }

    private Map<String, Object> destinationRow() {
        return jdbcTemplate.queryForMap("""
                select long_url, destination_checked_at, destination_dead_since, destination_fail_streak, destination_last_status
                from url where id = ?""", LINK);
    }

    private void link(final long visits, final Long visitLimit) {
        jdbcTemplate.update("""
                insert into url (id, long_url, short_url, visits, visit_limit, expiration_date, create_date, active)
                values (?, 'https://example.com/visited', ?, ?, ?, ?, ?, true)""",
                LINK, ALIAS, visits, visitLimit, EXPIRES, LocalDateTime.of(2026, 10, 1, 9, 0));
    }

    private Map<String, Object> linkRow() {
        return jdbcTemplate.queryForMap("select visits, last_accessed, active, expiration_date, block_reason from url where id = ?", LINK);
    }

    private static String locationOf(final View view) {
        assertThat(view).isInstanceOf(RedirectView.class);
        return ((RedirectView) view).getUrl();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, CustomDomainRepository.class, UserRepository.class, DomainRuleRepository.class,
                            CampaignRepository.class}))
    @Import({DefaultUrlService.class, DefaultModerationService.class, UrlUpdateDtoToUrlConverter.class, DestinationCheckRepository.class, UrlCache.class,
            DefaultSettingsTestConfiguration.class, AnonymousLinkSignalsTestConfiguration.class, HumanCheckTestConfiguration.class})
    static class TestApplication {
    }

}
