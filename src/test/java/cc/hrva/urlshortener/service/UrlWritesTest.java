package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.CreateUrlToUrlConverter;
import cc.hrva.urlshortener.converter.UrlToPeekUrlConverter;
import cc.hrva.urlshortener.converter.UrlUpdateDtoToUrlConverter;
import cc.hrva.urlshortener.dto.DestinationHealth;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.impl.DefaultModerationService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import cc.hrva.urlshortener.validator.UrlValidator;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
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
    private LinkCheckService linkCheckService;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private TaskScheduler taskScheduler;

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
        jdbcTemplate.update("delete from url where id = ?", LINK);
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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, UserRepository.class, DomainRuleRepository.class}))
    @Import({DefaultUrlService.class, DefaultModerationService.class, UrlUpdateDtoToUrlConverter.class, DestinationCheckRepository.class})
    static class TestApplication {
    }

}
