package cc.hrva.urlshortener.service.domain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.client.CloudflareResult;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.CustomDomainService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainNeedsAttention;
import cc.hrva.urlshortener.service.domain.CustomDomainLifecycle.LifecycleRun;
import cc.hrva.urlshortener.service.domain.FakeCloudflare.Call;
import cc.hrva.urlshortener.service.impl.DefaultCustomDomainService;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = CustomDomainLifecycleTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:custom-domain-lifecycle;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=https://app.hrva.cc",
                "app.server-url=https://hrva.cc",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.mcp.signing-key=unused-in-this-test",
                "app.domains.ignored-hostnames=test.karlo.cc"
        })
class CustomDomainLifecycleTest {

    private static final Instant START = Instant.parse("2026-10-29T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(START, ZoneOffset.UTC);
    private static final LocalDateTime APPROVED_AT = NOW.minusMinutes(20);
    private static final long ANA = 900L;
    private static final MutableClock CLOCK = new MutableClock(START);
    private static final FakeCloudflare CLOUDFLARE = new FakeCloudflare();
    private static final WindowLimiter LIMITER = new WindowLimiter();

    @Autowired
    private CustomDomainLifecycle lifecycle;

    @Autowired
    private CustomDomainProvisioner provisioner;

    @Autowired
    private CustomDomainService customDomainService;

    @Autowired
    private CustomDomainRepository customDomainRepository;

    @Autowired
    private CustomDomainHostnames customDomainHostnames;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionOperations transactionOperations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private TestApplication.CommittedEvents committedEvents;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private LinkCheckService linkCheckService;

    private final Logger lifecycleLogger = (Logger) LoggerFactory.getLogger(CustomDomainLifecycle.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    @BeforeEach
    void setUp() {
        CLOCK.set(START);
        CLOUDFLARE.reset();
        LIMITER.reset();
        committedEvents.clear();
        jdbcTemplate.update("insert into user_account (id, email, password, active, create_date) values (?, 'ana@hrva.cc', 'secret-hash', true, ?)",
                ANA, NOW.minusDays(30));
        logEvents.start();
        lifecycleLogger.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        appProperties.getDomains().setFreeAllowance(100);
        lifecycleLogger.detachAppender(logEvents);
        assertThat(CLOUDFLARE.callsInsideATransaction()).isEmpty();
        jdbcTemplate.update("delete from custom_domain");
        jdbcTemplate.update("delete from user_account where id = ?", ANA);
    }

    @Test
    void approvedIsCreatedAndAwaitsDns() {
        final var id = domain(ANA, "go.example.com", CustomDomainStatus.APPROVED_PROVISIONING, null);
        set(id, "reviewed_at = ?, next_check_at = ?", NOW, NOW);

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(1, 0, 0, 0));

        assertThat(CLOUDFLARE.calls()).containsExactly("FIND go.example.com", "TOTAL ", "CREATE go.example.com");
        assertThat(row(id))
                .containsEntry("STATUS", "AWAITING_DNS")
                .containsEntry("CLOUDFLARE_ID", "cf-1")
                .containsEntry("PROBLEM", null)
                .containsEntry("LAST_ERROR", null);
        assertThat(nextCheckAt(id)).isEqualTo(NOW.plusMinutes(1));
        assertThat(committedEvents.changes()).containsExactly(new CustomDomainChangedEvent(id));
        assertThat(committedEvents.alerts()).isEmpty();
    }

    @Test
    void aLostCreateResponseIsAdoptedNotDuplicated() {
        final var id = approved("go.example.com");
        CLOUDFLARE.storeTheNextCreateButLoseTheAnswer();

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 1));
        assertThat(row(id))
                .containsEntry("STATUS", "APPROVED_PROVISIONING")
                .containsEntry("PROBLEM", "PROVISIONING_FAILED")
                .containsEntry("LAST_ERROR", "Cloudflare couldn't be reached (timeout)")
                .containsEntry("CLOUDFLARE_ID", null);
        assertThat(nextCheckAt(id)).isEqualTo(NOW.plusMinutes(1));

        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(1, 0, 0, 0));

        assertThat(CLOUDFLARE.count(Call.CREATE)).isOne();
        assertThat(CLOUDFLARE.count(Call.FIND)).isEqualTo(2);
        assertThat(row(id))
                .containsEntry("STATUS", "AWAITING_DNS")
                .containsEntry("CLOUDFLARE_ID", "cf-1")
                .containsEntry("PROBLEM", null)
                .containsEntry("LAST_ERROR", null);
    }

    @Test
    void anExistingHostnameIsAdopted() {
        final var existing = CLOUDFLARE.store("test.karlo.cc", "active", "active");
        final var id = approved("test.karlo.cc");

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(1, 0, 0, 0));

        assertThat(CLOUDFLARE.calls()).containsExactly("FIND test.karlo.cc");
        assertThat(row(id)).containsEntry("STATUS", "AWAITING_DNS").containsEntry("CLOUDFLARE_ID", existing);
    }

    @Test
    void aDuplicateAnswerIsFollowedByOneMoreLookup() {
        final var existing = CLOUDFLARE.store("go.example.com", "pending", "pending_validation");
        CLOUDFLARE.script(Call.FIND, new CloudflareResult.Ok<>(Optional.empty(), 0));
        final var id = approved("go.example.com");

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(1, 0, 0, 0));

        assertThat(CLOUDFLARE.calls()).containsExactly("FIND go.example.com", "TOTAL ", "CREATE go.example.com", "FIND go.example.com");
        assertThat(row(id)).containsEntry("STATUS", "AWAITING_DNS").containsEntry("CLOUDFLARE_ID", existing);
    }

    @Test
    void aDuplicateThatCannotBeFoundIsAFailedAttempt() {
        CLOUDFLARE.script(Call.FIND, new CloudflareResult.Ok<>(Optional.empty(), 0));
        CLOUDFLARE.script(Call.CREATE, new CloudflareResult.Refused<>(409, 1406, "Duplicate custom hostname found."));
        CLOUDFLARE.script(Call.FIND, new CloudflareResult.Ok<>(Optional.empty(), 0));
        final var id = approved("go.example.com");

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 1));

        assertThat(CLOUDFLARE.calls()).containsExactly("FIND go.example.com", "TOTAL ", "CREATE go.example.com", "FIND go.example.com");
        assertThat(row(id)).containsEntry("STATUS", "APPROVED_PROVISIONING").containsEntry("PROBLEM", "PROVISIONING_FAILED")
                .containsEntry("LAST_ERROR", "Duplicate custom hostname found.");
    }

    @Test
    void aFailedLookupCreatesNothing() {
        CLOUDFLARE.script(Call.FIND, new CloudflareResult.Unreachable<>("server error"));
        final var id = approved("go.example.com");

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 1));

        assertThat(CLOUDFLARE.calls()).containsExactly("FIND go.example.com");
        assertThat(row(id)).containsEntry("LAST_ERROR", "Cloudflare couldn't be reached (server error)");
    }

    @Test
    void aFailedCreateRetriesThenErrors() {
        CLOUDFLARE.failEveryCreate(new CloudflareResult.Refused<>(400, 1409, "Hostname is not allowed."));
        final var id = domain(ANA, "go.example.com", CustomDomainStatus.APPROVED_PROVISIONING, null);
        set(id, "reviewed_at = ?, next_check_at = ?", NOW, NOW);
        final var retries = new ArrayList<LocalDateTime>();

        while (CLOCK.instant().isBefore(START.plus(Duration.ofHours(25)))) {
            lifecycle.runOnce();
            final var next = nextCheckAt(id);
            if (next == null) {
                break;
            }
            retries.add(next);
            CLOCK.set(next.toInstant(ZoneOffset.UTC));
        }

        assertThat(retries).startsWith(NOW.plusMinutes(1), NOW.plusMinutes(6), NOW.plusMinutes(36), NOW.plusMinutes(96), NOW.plusMinutes(156));
        assertThat(retries).last().isEqualTo(NOW.plusHours(24).plusMinutes(36));
        assertThat(row(id))
                .containsEntry("STATUS", "ERROR")
                .containsEntry("PROBLEM", "PROVISIONING_FAILED")
                .containsEntry("LAST_ERROR", "Hostname is not allowed.")
                .containsEntry("NEXT_CHECK_AT", null);
        assertThat(CLOUDFLARE.count(Call.CREATE)).isEqualTo(retries.size() + 1);
        assertThat(committedEvents.alerts()).containsExactly(new DomainNeedsAttention(id, "go.example.com", DomainProblem.PROVISIONING_FAILED));
        assertThat(committedEvents.changes()).containsExactly(new CustomDomainChangedEvent(id));

        CLOCK.advance(Duration.ofDays(1));
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 0));
    }

    @Test
    void eachFailedCreateShowsTheProblemAtOnce() {
        CLOUDFLARE.failEveryCreate(new CloudflareResult.Unreachable<>("server error"));
        final var id = approved("go.example.com");

        lifecycle.runOnce();

        assertThat(row(id)).containsEntry("STATUS", "APPROVED_PROVISIONING").containsEntry("PROBLEM", "PROVISIONING_FAILED");
        assertThat(committedEvents.alerts()).isEmpty();
    }

    @Test
    void pendingThenActive() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "pending", "pending_validation");
        CLOUDFLARE.state(cloudflareId, "pending", "pending_validation", List.of(FakeCloudflare.NOT_CNAMED), List.of());
        final var id = waiting("go.example.com", cloudflareId);

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));
        assertThat(row(id))
                .containsEntry("STATUS", "AWAITING_DNS")
                .containsEntry("PROBLEM", "DNS_NOT_FOUND")
                .containsEntry("CLOUDFLARE_STATUS", "pending")
                .containsEntry("CLOUDFLARE_SSL_STATUS", "pending_validation")
                .containsEntry("LAST_ERROR", FakeCloudflare.NOT_CNAMED)
                .containsEntry("ACTIVATED_AT", null);
        assertThat(nextCheckAt(id)).isEqualTo(NOW.plusMinutes(1));

        CLOCK.advance(Duration.ofMinutes(1));
        CLOUDFLARE.state(cloudflareId, "active", "pending_validation");
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));
        assertThat(row(id)).containsEntry("STATUS", "AWAITING_DNS").containsEntry("PROBLEM", null).containsEntry("CLOUDFLARE_STATUS", "active")
                .containsEntry("LAST_ERROR", null);

        CLOCK.advance(Duration.ofMinutes(1));
        CLOUDFLARE.state(cloudflareId, "active", "active");
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));
        final var activatedAt = NOW.plusMinutes(2);
        assertThat(row(id)).containsEntry("STATUS", "ACTIVE").containsEntry("PROBLEM", null).containsEntry("CLOUDFLARE_SSL_STATUS", "active");
        assertThat(activatedAt(id)).isEqualTo(activatedAt);
        assertThat(nextCheckAt(id)).isEqualTo(activatedAt.plusDays(1));
        assertThat(committedEvents.changes()).containsExactly(new CustomDomainChangedEvent(id));

        CLOCK.advance(Duration.ofDays(1));
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));
        assertThat(activatedAt(id)).isEqualTo(activatedAt);
        assertThat(row(id)).containsEntry("CHECK_COUNT", 4);
        assertThat(committedEvents.changes()).hasSize(1);
        assertThat(committedEvents.alerts()).isEmpty();
    }

    @Test
    void activeThenMovedIsCnameRemoved() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "moved", "active");
        final var id = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE, cloudflareId);
        set(id, "reviewed_at = ?, activated_at = ?, next_check_at = ?", NOW.minusDays(20), NOW.minusDays(20).plusMinutes(5), NOW);

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));
        assertThat(row(id)).containsEntry("STATUS", "ERROR").containsEntry("PROBLEM", "CNAME_REMOVED");
        assertThat(nextCheckAt(id)).isEqualTo(NOW.plusHours(1));

        CLOCK.advance(Duration.ofHours(1));
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));
        assertThat(row(id)).containsEntry("STATUS", "ERROR").containsEntry("PROBLEM", "CNAME_REMOVED");

        assertThat(committedEvents.alerts()).containsExactly(new DomainNeedsAttention(id, "go.example.com", DomainProblem.CNAME_REMOVED));
        assertThat(committedEvents.changes()).containsExactly(new CustomDomainChangedEvent(id));

        CLOUDFLARE.state(cloudflareId, "pending", "pending_validation");
        CLOCK.advance(Duration.ofHours(1));
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));
        assertThat(row(id)).containsEntry("STATUS", "AWAITING_DNS").containsEntry("PROBLEM", "DNS_NOT_FOUND");
        assertThat(nextCheckAt(id)).isEqualTo(NOW.plusHours(3));

        CLOUDFLARE.state(cloudflareId, "active", "active");
        CLOCK.advance(Duration.ofHours(1));
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));
        assertThat(row(id)).containsEntry("STATUS", "ACTIVE").containsEntry("PROBLEM", null);
        assertThat(committedEvents.alerts()).hasSize(1);
    }

    @Test
    void anActiveDomainWhoseCertificateIsRenewingStaysActive() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "active", "pending_validation");
        final var id = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE, cloudflareId);
        set(id, "activated_at = ?, next_check_at = ?, cloudflare_status = 'active', cloudflare_ssl_status = 'active'", APPROVED_AT.plusMinutes(5),
                NOW);

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));

        assertThat(row(id)).containsEntry("STATUS", "ACTIVE").containsEntry("PROBLEM", null)
                .containsEntry("CLOUDFLARE_SSL_STATUS", "pending_validation")
                .containsEntry("LAST_ERROR", "Certificate status in Cloudflare: pending_validation");
        assertThat(nextCheckAt(id)).isEqualTo(NOW.plusDays(1));
        assertThat(committedEvents.changes()).isEmpty();
        assertThat(committedEvents.alerts()).isEmpty();

        CLOUDFLARE.state(cloudflareId, "active", "validation_timed_out");
        CLOCK.advance(Duration.ofDays(1));
        lifecycle.runOnce();

        assertThat(row(id)).containsEntry("STATUS", "ACTIVE").containsEntry("PROBLEM", null);
        assertThat(committedEvents.changes()).isEmpty();
        assertThat(committedEvents.alerts()).isEmpty();

        CLOUDFLARE.state(cloudflareId, "active", "active");
        CLOCK.advance(Duration.ofDays(1));
        lifecycle.runOnce();

        assertThat(row(id)).containsEntry("STATUS", "ACTIVE").containsEntry("LAST_ERROR", null).containsEntry("CLOUDFLARE_SSL_STATUS", "active");
        assertThat(activatedAt(id)).isEqualTo(APPROVED_AT.plusMinutes(5));
    }

    @Test
    void blockedAlertsOnce() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "blocked", "pending_validation");
        final var id = waiting("go.example.com", cloudflareId);

        lifecycle.runOnce();
        CLOCK.advance(Duration.ofHours(1));
        lifecycle.runOnce();

        assertThat(row(id)).containsEntry("STATUS", "ERROR").containsEntry("PROBLEM", "CLOUDFLARE_BLOCKED");
        assertThat(nextCheckAt(id)).isEqualTo(NOW.plusHours(2));
        assertThat(committedEvents.alerts()).containsExactly(new DomainNeedsAttention(id, "go.example.com", DomainProblem.CLOUDFLARE_BLOCKED));

        CLOUDFLARE.state(cloudflareId, "active", "active");
        CLOCK.advance(Duration.ofHours(1));
        lifecycle.runOnce();

        assertThat(row(id)).containsEntry("STATUS", "ACTIVE").containsEntry("PROBLEM", null);
        assertThat(committedEvents.alerts()).hasSize(1);
        assertThat(committedEvents.changes()).containsExactly(new CustomDomainChangedEvent(id), new CustomDomainChangedEvent(id));
    }

    @Test
    void aHostnameGoneFromCloudflareWaitsForARetry() {
        final var id = waiting("go.example.com", "cf-404");

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));

        assertThat(row(id)).containsEntry("STATUS", "ERROR").containsEntry("PROBLEM", "PROVISIONING_FAILED")
                .containsEntry("LAST_ERROR", "Cloudflare no longer has this hostname").containsEntry("NEXT_CHECK_AT", null);
        assertThat(committedEvents.alerts()).containsExactly(new DomainNeedsAttention(id, "go.example.com", DomainProblem.PROVISIONING_FAILED));
        CLOCK.advance(Duration.ofDays(2));
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 0));
    }

    @Test
    void aFailedCheckTriesAgainOnScheduleAndChangesNothingElse() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "pending", "pending_validation");
        final var id = waiting("go.example.com", cloudflareId);
        set(id, "problem = 'DNS_NOT_FOUND', last_error = 'earlier'");
        CLOUDFLARE.script(Call.GET, new CloudflareResult.Unreachable<>("timeout"));

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 1));

        assertThat(row(id)).containsEntry("STATUS", "AWAITING_DNS").containsEntry("PROBLEM", "DNS_NOT_FOUND").containsEntry("LAST_ERROR", "earlier")
                .containsEntry("LAST_CHECKED_AT", null);
        assertThat(nextCheckAt(id)).isEqualTo(NOW.plusMinutes(1));
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 0));
    }

    @Test
    void afterFourteenDaysWithoutTheCnameTheChecksStop() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "pending", "pending_validation");
        final var id = waiting("go.example.com", cloudflareId);
        set(id, "reviewed_at = ?", NOW.minusDays(14).minusMinutes(1));

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 1, 0, 0));

        assertThat(row(id)).containsEntry("STATUS", "ERROR").containsEntry("PROBLEM", "DNS_TIMED_OUT").containsEntry("NEXT_CHECK_AT", null);
        assertThat(committedEvents.changes()).containsExactly(new CustomDomainChangedEvent(id));
        assertThat(committedEvents.alerts()).isEmpty();
        CLOCK.advance(Duration.ofDays(3));
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 0));
    }

    @Test
    void aDeletedHostnameIsDeletedOnce() {
        final var first = CLOUDFLARE.store("go.example.com", "active", "active");
        final var id = queuedDelete(ANA, "go.example.com", first);
        final var gone = queuedDelete(ANA, "gone.example.com", "cf-404");
        CLOUDFLARE.script(Call.DELETE, new CloudflareResult.Unreachable<>("timeout"));
        CLOUDFLARE.script(Call.DELETE, new CloudflareResult.NotFound<>());

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 1, 1));
        assertThat(row(id)).containsEntry("CLOUDFLARE_DELETE_PENDING", true).containsEntry("HOSTNAME_KEY", "go.example.com");
        assertThat(row(gone)).containsEntry("CLOUDFLARE_DELETE_PENDING", false).containsEntry("HOSTNAME_KEY", null)
                .containsEntry("STATUS", "DELETED");
        assertThat(CLOUDFLARE.has(first)).isTrue();

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 1, 0));
        assertThat(row(id)).containsEntry("CLOUDFLARE_DELETE_PENDING", false).containsEntry("HOSTNAME_KEY", null).containsEntry("STATUS", "DELETED");
        assertThat(CLOUDFLARE.has(first)).isFalse();

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 0));
        assertThat(CLOUDFLARE.count(Call.DELETE)).isEqualTo(3);
    }

    @Test
    void anOwnerlessRowIsGoneAfterItsDelete() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "active", "active");
        final var orphan = queuedDelete(null, "go.example.com", cloudflareId);
        final var owned = queuedDelete(ANA, "links.example.org", CLOUDFLARE.store("links.example.org", "active", "active"));

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 2, 0));

        assertThat(jdbcTemplate.queryForObject("select count(*) from custom_domain where id = ?", Integer.class, orphan)).isZero();
        assertThat(row(owned)).containsEntry("STATUS", "DELETED").containsEntry("CLOUDFLARE_DELETE_PENDING", false);
    }

    @Test
    void anIgnoredHostnameIsNeverDeletedInCloudflare() {
        final var cloudflareId = CLOUDFLARE.store("test.karlo.cc", "active", "active");
        final var id = queuedDelete(ANA, "test.karlo.cc", cloudflareId);

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 1, 0));

        assertThat(CLOUDFLARE.count(Call.DELETE)).isZero();
        assertThat(CLOUDFLARE.has(cloudflareId)).isTrue();
        assertThat(row(id)).containsEntry("CLOUDFLARE_DELETE_PENDING", false).containsEntry("HOSTNAME_KEY", null);
    }

    @Test
    void aHostnameCreatedForADomainRemovedMeanwhileIsDeletedAgain() {
        final var id = approved("go.example.com");
        CLOUDFLARE.duringTheNextCreate(() -> set(id, "status = 'DELETED', hostname_key = null, deleted_at = ?", NOW));

        assertThat(lifecycle.runOnce().provisioned()).isZero();

        assertThat(CLOUDFLARE.calls()).containsExactly("FIND go.example.com", "TOTAL ", "CREATE go.example.com", "DELETE cf-1");
        assertThat(CLOUDFLARE.has("cf-1")).isFalse();
        assertThat(row(id)).containsEntry("STATUS", "DELETED").containsEntry("CLOUDFLARE_ID", null);
    }

    @Test
    void aHostnameCreatedForADomainTurnedOffMeanwhileIsKeptWithIt() {
        final var id = approved("go.example.com");
        CLOUDFLARE.duringTheNextCreate(() -> set(id, "status = 'DISABLED', disabled_reason = 'ADMIN', disabled_at = ?", NOW));

        assertThat(lifecycle.runOnce().provisioned()).isZero();

        assertThat(CLOUDFLARE.calls()).containsExactly("FIND go.example.com", "TOTAL ", "CREATE go.example.com");
        assertThat(CLOUDFLARE.has("cf-1")).isTrue();
        assertThat(row(id)).containsEntry("STATUS", "DISABLED").containsEntry("CLOUDFLARE_ID", "cf-1");
        assertThat(customDomainRepository.countSlotsInUse()).isOne();
    }

    @Test
    void aCreateWaitsWhileCloudflaresFreeAllowanceIsFull() {
        appProperties.getDomains().setFreeAllowance(2);
        CLOUDFLARE.store("one.example.net", "active", "active");
        final var id = approved("go.example.com");
        final var inFlight = approved("other.example.org");
        set(inFlight, "next_check_at = ?", NOW.plusHours(1));

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 1));

        assertThat(CLOUDFLARE.calls()).containsExactly("FIND go.example.com", "TOTAL ");
        assertThat(row(id)).containsEntry("STATUS", "APPROVED_PROVISIONING").containsEntry("PROBLEM", "PROVISIONING_FAILED")
                .containsEntry("LAST_ERROR", "Cloudflare's free allowance is full: 2 of 2 custom hostnames are taken or being set up.");
        assertThat(nextCheckAt(id)).isEqualTo(NOW.plusMinutes(1));

        set(inFlight, "status = 'REJECTED', hostname_key = null");
        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(1, 0, 0, 0));
        assertThat(row(id)).containsEntry("STATUS", "AWAITING_DNS");
    }

    @Test
    void aFailedFreeAllowanceProbeCreatesNothing() {
        CLOUDFLARE.script(Call.TOTAL, new CloudflareResult.Unreachable<>("timeout"));
        final var id = approved("go.example.com");

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 1));

        assertThat(CLOUDFLARE.calls()).containsExactly("FIND go.example.com", "TOTAL ");
        assertThat(row(id)).containsEntry("LAST_ERROR", "Cloudflare couldn't be reached (timeout)");
    }

    @Test
    void pendingWorkIsPickedUpByTheNextRun() {
        final var created = approved("new.example.com");
        final var checkedId = CLOUDFLARE.store("go.example.com", "active", "active");
        final var checked = waiting("go.example.com", checkedId);
        final var deletedId = CLOUDFLARE.store("old.example.com", "active", "active");
        final var deleted = queuedDelete(ANA, "old.example.com", deletedId);
        final var afterRestart = new CustomDomainLifecycle(customDomainRepository, CLOUDFLARE,
                new CustomDomainProvisioner(customDomainRepository, CLOUDFLARE, eventPublisher, transactionOperations, appProperties, CLOCK),
                customDomainHostnames, eventPublisher, transactionOperations, CLOCK);

        assertThat(afterRestart.runOnce()).isEqualTo(new LifecycleRun(1, 1, 1, 0));

        assertThat(row(created)).containsEntry("STATUS", "AWAITING_DNS");
        assertThat(row(checked)).containsEntry("STATUS", "ACTIVE");
        assertThat(row(deleted)).containsEntry("CLOUDFLARE_DELETE_PENDING", false);
        assertThat(CLOUDFLARE.has(deletedId)).isFalse();
    }

    @Test
    void anOverlappingRunIsSkipped() throws Exception {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "active", "active");
        final var id = waiting("go.example.com", cloudflareId);
        final var entered = new CountDownLatch(1);
        final var released = new CountDownLatch(1);
        CLOUDFLARE.blockTheNext(Call.GET, entered, released);
        final var first = Thread.ofPlatform().start(lifecycle::runOnce);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 0));

        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage)
                .contains("Custom domain lifecycle skipped: the previous run is still going");
        released.countDown();
        first.join(10_000);
        assertThat(CLOUDFLARE.count(Call.GET)).isOne();
        assertThat(row(id)).containsEntry("STATUS", "ACTIVE");
    }

    @Test
    void anIdleRunLogsNothing() {
        lifecycle.runOnce();

        assertThat(logEvents.list).extracting(ILoggingEvent::getLevel).doesNotContain(Level.INFO);
    }

    @Test
    void aRunThatDidSomethingLogsItsCounts() {
        waiting("go.example.com", CLOUDFLARE.store("go.example.com", "active", "active"));

        lifecycle.runOnce();

        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage)
                .contains("Custom domain lifecycle provisioned=0 checked=1 deleted=0 failed=0");
    }

    @Test
    void noTransactionIsOpenDuringACloudflareCall() {
        final var created = approved("new.example.com");
        final var checked = waiting("go.example.com", CLOUDFLARE.store("go.example.com", "pending", "pending_validation"));
        final var deleted = queuedDelete(ANA, "old.example.com", CLOUDFLARE.store("old.example.com", "active", "active"));
        final var viewed = waiting("links.example.org", CLOUDFLARE.store("links.example.org", "active", "active"));
        final var provisioned = approved("menu.example.net");
        signedIn();

        transactionOperations.executeWithoutResult(status -> {
            lifecycle.checkNow(checked);
            lifecycle.deleteNow(deleted);
            provisioner.provision(created);
            customDomainService.get(viewed);
            lifecycle.runOnce();
        });

        assertThat(CLOUDFLARE.calls()).contains("GET cf-1", "DELETE cf-2", "CREATE new.example.com", "GET cf-3", "CREATE menu.example.net");
        assertThat(CLOUDFLARE.callsInsideATransaction()).isEmpty();
        assertThat(row(provisioned)).containsEntry("STATUS", "AWAITING_DNS");
    }

    @Test
    void noDatabaseConnectionIsHeldDuringACloudflareCall() {
        approved("new.example.com");
        waiting("go.example.com", CLOUDFLARE.store("go.example.com", "pending", "pending_validation"));
        queuedDelete(ANA, "old.example.com", CLOUDFLARE.store("old.example.com", "active", "active"));
        final var viewed = waiting("links.example.org", CLOUDFLARE.store("links.example.org", "active", "active"));
        set(viewed, "next_check_at = ?", NOW.plusHours(1));
        signedIn();
        CLOUDFLARE.watchConnections(() -> ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections());

        lifecycle.runOnce();
        customDomainService.get(viewed);

        assertThat(CLOUDFLARE.calls()).contains("FIND new.example.com", "TOTAL ", "CREATE new.example.com", "GET cf-1", "DELETE cf-2", "GET cf-3");
        assertThat(CLOUDFLARE.callsHoldingAConnection()).isEmpty();
    }

    @Test
    void theUserPageChecksAtMostEvery15Seconds() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "pending", "pending_validation");
        final var id = waiting("go.example.com", cloudflareId);
        signedIn();

        customDomainService.get(id);
        CLOUDFLARE.state(cloudflareId, "active", "active");
        assertThat(customDomainService.get(id).status()).isEqualTo(CustomDomainStatus.AWAITING_DNS);
        assertThat(CLOUDFLARE.count(Call.GET)).isOne();

        CLOCK.advance(Duration.ofSeconds(15));
        assertThat(customDomainService.get(id).status()).isEqualTo(CustomDomainStatus.ACTIVE);
        assertThat(CLOUDFLARE.count(Call.GET)).isEqualTo(2);
        assertThat(LIMITER.keys()).containsOnly("domain-check:domain:" + id);

        CLOUDFLARE.state(cloudflareId, "moved", "active");
        set(id, "status = 'AWAITING_DNS'");
        CLOCK.advance(Duration.ofSeconds(15));
        LIMITER.down = true;
        final var stored = customDomainService.get(id);

        assertThat(stored.status()).isEqualTo(CustomDomainStatus.AWAITING_DNS);
        assertThat(CLOUDFLARE.count(Call.GET)).isEqualTo(2);
    }

    @Test
    void theUserPageSetsUpAnApprovedDomainOnlyWhenItIsDue() {
        final var id = approved("go.example.com");
        set(id, "next_check_at = ?, problem = 'PROVISIONING_FAILED'", NOW.plusMinutes(5));
        signedIn();

        assertThat(customDomainService.get(id).status()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
        assertThat(CLOUDFLARE.calls()).isEmpty();

        CLOCK.advance(Duration.ofMinutes(5));
        assertThat(customDomainService.get(id).status()).isEqualTo(CustomDomainStatus.AWAITING_DNS);
        assertThat(CLOUDFLARE.calls()).containsExactly("FIND go.example.com", "TOTAL ", "CREATE go.example.com");
    }

    @Test
    void theUserPageChecksAStoppedDomainButNotAFailedSetupOrAnActiveOne() {
        final var timedOutId = CLOUDFLARE.store("go.example.com", "active", "active");
        final var timedOut = waiting("go.example.com", timedOutId);
        set(timedOut, "status = 'ERROR', problem = 'DNS_TIMED_OUT', next_check_at = null, reviewed_at = ?", NOW.minusDays(15));
        final var failed = domain(ANA, "failed.example.com", CustomDomainStatus.ERROR, null);
        set(failed, "problem = 'PROVISIONING_FAILED', next_check_at = null");
        final var active = domain(ANA, "active.example.com", CustomDomainStatus.ACTIVE, CLOUDFLARE.store("active.example.com", "moved", "active"));
        signedIn();

        assertThat(customDomainService.get(timedOut).status()).isEqualTo(CustomDomainStatus.ACTIVE);
        assertThat(customDomainService.get(failed).status()).isEqualTo(CustomDomainStatus.ERROR);
        assertThat(customDomainService.get(active).status()).isEqualTo(CustomDomainStatus.ACTIVE);
        assertThat(CLOUDFLARE.calls()).containsExactly("GET " + timedOutId);
    }

    @Test
    void aStoppedDomainCheckedOnTheUserPageStaysStoppedWithoutTheCname() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "pending", "pending_validation");
        final var id = waiting("go.example.com", cloudflareId);
        set(id, "status = 'ERROR', problem = 'DNS_TIMED_OUT', next_check_at = null, reviewed_at = ?", NOW.minusDays(15));
        signedIn();

        assertThat(customDomainService.get(id).problem()).isEqualTo(DomainProblem.DNS_TIMED_OUT);

        assertThat(row(id)).containsEntry("STATUS", "ERROR").containsEntry("NEXT_CHECK_AT", null).containsEntry("CHECK_COUNT", 1);
        assertThat(committedEvents.changes()).isEmpty();
    }

    private void signedIn() {
        final User ana = userRepository.findById(ANA).orElseThrow();
        when(userService.getUserFromToken()).thenReturn(ana);
    }

    private long approved(final String hostname) {
        final var id = domain(ANA, hostname, CustomDomainStatus.APPROVED_PROVISIONING, null);
        set(id, "next_check_at = ?", NOW);
        return id;
    }

    private long waiting(final String hostname, final String cloudflareId) {
        return domain(ANA, hostname, CustomDomainStatus.AWAITING_DNS, cloudflareId);
    }

    private long queuedDelete(final Long ownerId, final String hostname, final String cloudflareId) {
        final var id = domain(ownerId, hostname, CustomDomainStatus.DELETED, cloudflareId);
        set(id, "cloudflare_delete_pending = true, deleted_at = ?, next_check_at = null", NOW.minusMinutes(1));
        return id;
    }

    private long domain(final Long ownerId, final String hostname, final CustomDomainStatus status, final String cloudflareId) {
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, cloudflare_id, reviewed_at, next_check_at,
                                           cloudflare_delete_pending, created_at, updated_at)
                values (?, ?, ?, ?, 'Menu links for my cafe', ?, ?, ?, false, ?, ?)""",
                ownerId, hostname, hostname, status.name(), cloudflareId, APPROVED_AT, NOW, NOW.minusDays(1), NOW.minusDays(1));
        return jdbcTemplate.queryForObject("select id from custom_domain where hostname = ?", Long.class, hostname);
    }

    private void set(final long id, final String assignments, final Object... values) {
        final var arguments = new ArrayList<>(List.of(values));
        arguments.add(id);
        jdbcTemplate.update("update custom_domain set " + assignments + " where id = ?", arguments.toArray());
    }

    private Map<String, Object> row(final long id) {
        return jdbcTemplate.queryForMap("select * from custom_domain where id = ?", id);
    }

    private LocalDateTime nextCheckAt(final long id) {
        return jdbcTemplate.queryForObject("select next_check_at from custom_domain where id = ?", LocalDateTime.class, id);
    }

    private LocalDateTime activatedAt(final long id) {
        return jdbcTemplate.queryForObject("select activated_at from custom_domain where id = ?", LocalDateTime.class, id);
    }

    static final class WindowLimiter implements RateLimiter {

        private final Map<String, Instant> windowStarts = new HashMap<>();
        private final Map<String, Long> counts = new HashMap<>();
        private final List<String> keys = new CopyOnWriteArrayList<>();
        volatile boolean down;

        synchronized void reset() {
            windowStarts.clear();
            counts.clear();
            keys.clear();
            down = false;
        }

        List<String> keys() {
            return List.copyOf(keys);
        }

        @Override
        public synchronized long increment(final String key, final Duration window) {
            keys.add(key);
            if (down) {
                throw new RateLimiterUnavailableException(new IllegalStateException("Redis is down"));
            }
            final var now = CLOCK.instant();
            final var start = windowStarts.get(key);
            if (start == null || !now.isBefore(start.plus(window))) {
                windowStarts.put(key, now);
                counts.put(key, 0L);
            }
            return counts.merge(key, 1L, Long::sum);
        }

    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, CustomDomainRepository.class, CustomDomainAllowanceRepository.class, UrlRepository.class}))
    @Import({
            AppConfiguration.class,
            AccountLock.class,
            CustomDomainLifecycle.class,
            CustomDomainProvisioner.class,
            CustomDomainHostnames.class,
            CustomDomainLimits.class,
            CustomDomainResponses.class,
            DefaultCustomDomainService.class,
            TestApplication.Fixed.class,
            TestApplication.CommittedEvents.class
    })
    static class TestApplication {

        static class CommittedEvents {

            private final List<CustomDomainChangedEvent> changes = new CopyOnWriteArrayList<>();
            private final List<AdminAlertEvent> alerts = new CopyOnWriteArrayList<>();

            @TransactionalEventListener
            public void onChange(final CustomDomainChangedEvent change) {
                changes.add(change);
            }

            @TransactionalEventListener
            public void onAlert(final AdminAlertEvent alert) {
                alerts.add(alert);
            }

            List<CustomDomainChangedEvent> changes() {
                return List.copyOf(changes);
            }

            List<AdminAlertEvent> alerts() {
                return List.copyOf(alerts);
            }

            void clear() {
                changes.clear();
                alerts.clear();
            }

        }

        @TestConfiguration
        static class Fixed {

            @Bean
            MutableClock clock() {
                return CLOCK;
            }

            @Bean
            CloudflareClient cloudflareClient() {
                return CLOUDFLARE;
            }

            @Bean
            RateLimiter rateLimiter() {
                return LIMITER;
            }

        }

    }

}
