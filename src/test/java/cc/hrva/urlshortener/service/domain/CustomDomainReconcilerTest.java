package cc.hrva.urlshortener.service.domain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.client.CloudflareHostname;
import cc.hrva.urlshortener.client.CloudflareResult;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.MissingDomain;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.ReconcileRun;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainDrift;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck.TokenState;
import cc.hrva.urlshortener.service.domain.CustomDomainReconciler.QuotaReading;
import cc.hrva.urlshortener.service.domain.FakeCloudflare.Call;
import cc.hrva.urlshortener.service.notification.MutableClock;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = CustomDomainReconcilerTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:custom-domain-reconcile;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
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
class CustomDomainReconcilerTest {

    private static final Instant START = Instant.parse("2026-10-30T02:40:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(START, ZoneOffset.UTC);
    private static final long ANA = 910L;
    private static final MutableClock CLOCK = new MutableClock(START);
    private static final FakeCloudflare CLOUDFLARE = new FakeCloudflare();

    @Autowired
    private CustomDomainRepository customDomainRepository;

    @Autowired
    private CustomDomainReconcileRepository customDomainReconcileRepository;

    @Autowired
    private CustomDomainLifecycle customDomainLifecycle;

    @Autowired
    private CustomDomainHostnames customDomainHostnames;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionOperations transactionOperations;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestApplication.CommittedEvents committedEvents;

    private final CloudflareTokenCheck cloudflareTokenCheck = mock(CloudflareTokenCheck.class);
    private final Logger reconcilerLogger = (Logger) LoggerFactory.getLogger(CustomDomainReconciler.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private CustomDomainReconciler reconciler;

    @BeforeEach
    void setUp() {
        CLOCK.set(START);
        CLOUDFLARE.reset();
        committedEvents.clear();
        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW, null, 7));
        jdbcTemplate.update("insert into user_account (id, email, password, active, create_date) values (?, 'ana@hrva.cc', 'secret-hash', true, ?)",
                ANA, NOW.minusDays(30));
        reconciler = new CustomDomainReconciler(CLOUDFLARE, cloudflareTokenCheck, customDomainRepository, customDomainReconcileRepository,
                customDomainLifecycle, customDomainHostnames, eventPublisher, transactionOperations, appProperties, CLOCK);
        logEvents.start();
        reconcilerLogger.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        reconcilerLogger.detachAppender(logEvents);
        assertThat(CLOUDFLARE.callsInsideATransaction()).isEmpty();
        jdbcTemplate.update("delete from custom_domain_reconcile");
        jdbcTemplate.update("delete from custom_domain");
        jdbcTemplate.update("delete from user_account where id = ?", ANA);
    }

    @Test
    void unknownHostnamesAreDriftButIgnoredOnesAreNot() {
        CLOUDFLARE.store("test.karlo.cc", "active", "active");
        CLOUDFLARE.store("stranger.example.net", "active", "active");
        final var ours = domain("go.example.com", CustomDomainStatus.ACTIVE, CLOUDFLARE.store("go.example.com", "active", "active"));
        domain("new.example.com", CustomDomainStatus.APPROVED_PROVISIONING, null);
        CLOUDFLARE.store("new.example.com", "pending", "pending_validation");

        final var run = reconciler.reconcile().orElseThrow();

        assertThat(run.unknownHostnames()).containsExactly("stranger.example.net");
        assertThat(run.missingDomains()).isEmpty();
        assertThat(run.oursInCloudflare()).isOne();
        assertThat(run.cloudflareTotal()).isEqualTo(4);
        assertThat(run.error()).isNull();
        assertThat(committedEvents.alerts()).containsExactly(new DomainDrift(List.of("stranger.example.net"), List.of(), 4, 100));
        assertThat(statusOf(ours)).isEqualTo("ACTIVE");
    }

    @Test
    void oursMissingInCloudflareBecomeErrors() {
        final var active = domain("go.example.com", CustomDomainStatus.ACTIVE, "cf-404");
        final var disabled = domain("off.example.com", CustomDomainStatus.DISABLED, "cf-405");
        final var deleted = domain("old.example.com", CustomDomainStatus.DELETED, "cf-406");
        set(deleted, "cloudflare_delete_pending = true");
        domain("new.example.com", CustomDomainStatus.APPROVED_PROVISIONING, "cf-407");
        domain("live.example.com", CustomDomainStatus.AWAITING_DNS, CLOUDFLARE.store("live.example.com", "pending", "pending_validation"));

        final var run = reconciler.reconcile().orElseThrow();

        assertThat(run.missingDomains()).containsExactly(new MissingDomain(active, "go.example.com"), new MissingDomain(disabled, "off.example.com"));
        assertThat(run.oursInCloudflare()).isOne();
        assertThat(jdbcTemplate.queryForMap("select status, problem, last_error, next_check_at from custom_domain where id = ?", active))
                .containsEntry("STATUS", "ERROR").containsEntry("PROBLEM", "PROVISIONING_FAILED")
                .containsEntry("LAST_ERROR", "Cloudflare no longer has this hostname").containsEntry("NEXT_CHECK_AT", null);
        assertThat(statusOf(disabled)).isEqualTo("DISABLED");
        assertThat(committedEvents.changes()).containsExactly(new CustomDomainChangedEvent(active));
        assertThat(committedEvents.alerts()).containsExactly(new DomainDrift(List.of(), List.of("go.example.com", "off.example.com"), 1, 100));
        assertThat(CLOUDFLARE.calls()).contains("GET cf-404", "GET cf-405").doesNotContain("GET cf-406", "GET cf-407");
    }

    @Test
    void aMissingDomainIsAlertedOnTheFirstNightOnly() {
        final var missing = domain("go.example.com", CustomDomainStatus.ACTIVE, "cf-404");

        reconciler.reconcile();
        CLOCK.advance(Duration.ofDays(1));
        final var secondNight = reconciler.reconcile().orElseThrow();

        assertThat(secondNight.missingDomains()).containsExactly(new MissingDomain(missing, "go.example.com"));
        assertThat(committedEvents.alerts()).containsExactly(new DomainDrift(List.of(), List.of("go.example.com"), 0, 100));

        CLOUDFLARE.store("stranger.example.net", "active", "active");
        CLOCK.advance(Duration.ofDays(1));
        reconciler.reconcile();

        assertThat(committedEvents.alerts()).last().isEqualTo(new DomainDrift(List.of("stranger.example.net"), List.of(), 1, 100));
    }

    @Test
    void aHostnameTheListingSkippedIsAskedForBeforeItIsMarked() {
        final var skipped = CLOUDFLARE.store("go.example.com", "active", "active");
        final var id = domain("go.example.com", CustomDomainStatus.ACTIVE, skipped);
        CLOUDFLARE.script(Call.LIST, new CloudflareResult.Ok<>(List.<CloudflareHostname>of(), 1));

        final var run = reconciler.reconcile().orElseThrow();

        assertThat(run.missingDomains()).isEmpty();
        assertThat(run.oursInCloudflare()).isOne();
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        assertThat(committedEvents.alerts()).isEmpty();
    }

    @Test
    void nothingIsEverDeleted() {
        CLOUDFLARE.store("stranger.example.net", "active", "active");
        CLOUDFLARE.store("test.karlo.cc", "active", "active");
        domain("go.example.com", CustomDomainStatus.ACTIVE, "cf-404");

        reconciler.reconcile();

        assertThat(CLOUDFLARE.count(Call.DELETE)).isZero();
        assertThat(CLOUDFLARE.count(Call.CREATE)).isZero();
        assertThat(CLOUDFLARE.has("cf-1")).isTrue();
    }

    @Test
    void everyPageIsRead() {
        IntStream.rangeClosed(1, 120).forEach(index -> CLOUDFLARE.store("h" + index + ".example.com", "active", "active"));

        final var run = reconciler.reconcile().orElseThrow();

        assertThat(CLOUDFLARE.calls()).filteredOn(call -> call.startsWith("LIST")).containsExactly("LIST 1", "LIST 2", "LIST 3");
        assertThat(run.unknownHostnames()).hasSize(120);
        assertThat(run.cloudflareTotal()).isEqualTo(120);
    }

    @Test
    void aFullLastPageIsFollowedByOneMoreAndTheTotalAloneNeverStopsIt() {
        IntStream.rangeClosed(1, 100).forEach(index -> CLOUDFLARE.store("h" + index + ".example.com", "active", "active"));
        CLOUDFLARE.script(Call.LIST, new CloudflareResult.Ok<>(hostnames(50), 50));

        final var run = reconciler.reconcile().orElseThrow();

        assertThat(CLOUDFLARE.calls()).filteredOn(call -> call.startsWith("LIST")).containsExactly("LIST 1", "LIST 2", "LIST 3");
        assertThat(run.unknownHostnames()).hasSize(100);
        assertThat(run.cloudflareTotal()).isEqualTo(50);
    }

    @Test
    void aFailedPageRecordsTheErrorAndMarksNothing() {
        final var ours = domain("go.example.com", CustomDomainStatus.ACTIVE, "cf-404");
        CLOUDFLARE.script(Call.LIST, new CloudflareResult.Ok<>(hostnames(50), 60));
        CLOUDFLARE.script(Call.LIST, new CloudflareResult.Unreachable<>("timeout"));

        final var run = reconciler.reconcile().orElseThrow();

        assertThat(run).isEqualTo(new ReconcileRun(NOW, 7, null, List.of(), List.of(),
                "Listing custom hostnames failed on page 2: Cloudflare couldn't be reached (timeout)"));
        assertThat(statusOf(ours)).isEqualTo("ACTIVE");
        assertThat(CLOUDFLARE.calls()).containsExactly("LIST 1", "LIST 2");
        assertThat(committedEvents.alerts()).isEmpty();
        assertThat(customDomainReconcileRepository.newest()).contains(run);
    }

    @Test
    void aTokenThatDoesNotWorkRecordsTheProblemAndListsNothing() {
        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.MISSING, NOW,
                "CLOUDFLARE_API_TOKEN or CLOUDFLARE_ZONE_ID isn't set.", null));

        final var run = reconciler.reconcile().orElseThrow();

        assertThat(run).isEqualTo(new ReconcileRun(NOW, null, null, List.of(), List.of(), "CLOUDFLARE_API_TOKEN or CLOUDFLARE_ZONE_ID isn't set."));
        assertThat(CLOUDFLARE.calls()).isEmpty();
        assertThat(committedEvents.alerts()).isEmpty();
    }

    @Test
    void driftAlertsOnceAndCleanRunsDoNot() {
        CLOUDFLARE.store("stranger.example.net", "active", "active");
        CLOUDFLARE.store("other.example.net", "active", "active");
        domain("go.example.com", CustomDomainStatus.ACTIVE, "cf-404");

        reconciler.reconcile();

        assertThat(committedEvents.alerts()).containsExactly(
                new DomainDrift(List.of("other.example.net", "stranger.example.net"), List.of("go.example.com"), 2, 100));

        committedEvents.clear();
        jdbcTemplate.update("delete from custom_domain");
        CLOUDFLARE.reset();
        CLOUDFLARE.store("test.karlo.cc", "active", "active");
        domain("go.example.com", CustomDomainStatus.ACTIVE, CLOUDFLARE.store("go.example.com", "active", "active"));
        CLOCK.advance(Duration.ofDays(1));

        final var clean = reconciler.reconcile().orElseThrow();

        assertThat(clean.unknownHostnames()).isEmpty();
        assertThat(clean.missingDomains()).isEmpty();
        assertThat(committedEvents.alerts()).isEmpty();
    }

    @Test
    void theRunAndQuotaAreRecorded() {
        domain("go.example.com", CustomDomainStatus.ACTIVE, CLOUDFLARE.store("go.example.com", "active", "active"));
        CLOUDFLARE.store("test.karlo.cc", "active", "active");
        assertThat(reconciler.lastQuota()).isEmpty();

        final var run = reconciler.reconcile().orElseThrow();

        assertThat(run).isEqualTo(new ReconcileRun(NOW, 2, 1, List.of(), List.of(), null));
        assertThat(customDomainReconcileRepository.newest()).contains(run);
        assertThat(reconciler.lastQuota()).contains(new QuotaReading(2, 50_000, NOW));

        CLOCK.advance(Duration.ofDays(1));
        CLOUDFLARE.script(Call.QUOTA, new CloudflareResult.Unreachable<>("timeout"));
        reconciler.reconcile();

        assertThat(reconciler.lastQuota()).contains(new QuotaReading(2, 50_000, NOW));
        assertThat(customDomainReconcileRepository.newest().map(ReconcileRun::ranAt)).contains(NOW.plusDays(1));
    }

    @Test
    void oldRunsAreDeleted() {
        customDomainReconcileRepository.insert(new ReconcileRun(NOW.minusDays(31), 1, 1, List.of(), List.of(), null));
        customDomainReconcileRepository.insert(new ReconcileRun(NOW.minusDays(29), 1, 1, List.of(), List.of(), null));

        reconciler.reconcile();

        assertThat(jdbcTemplate.queryForList("select ran_at from custom_domain_reconcile order by ran_at", LocalDateTime.class))
                .containsExactly(NOW.minusDays(29), NOW);
    }

    @Test
    void startupCatchUpRunsOnlyWhenStale() {
        customDomainReconcileRepository.insert(new ReconcileRun(NOW.minusHours(25), 1, 1, List.of(), List.of(), null));

        reconciler.catchUpAtStartup();

        assertThat(CLOUDFLARE.calls()).isEmpty();
        assertThat(rowCount()).isOne();

        CLOCK.advance(Duration.ofHours(2));
        reconciler.catchUpAtStartup();

        assertThat(CLOUDFLARE.calls()).filteredOn(call -> call.startsWith("LIST")).hasSize(1);
        assertThat(rowCount()).isEqualTo(2);
        assertThat(customDomainReconcileRepository.newest().map(ReconcileRun::ranAt)).contains(NOW.plusHours(2));
    }

    @Test
    void startupCatchUpRunsWhenThereWasNeverARun() {
        reconciler.catchUpAtStartup();

        assertThat(rowCount()).isOne();
    }

    @Test
    void aStartupCatchUpThatFailsNeverStopsTheApplication() {
        when(cloudflareTokenCheck.check()).thenThrow(new IllegalStateException("boom"));

        reconciler.catchUpAtStartup();

        assertThat(rowCount()).isZero();
        doReturn(new TokenState(CloudflareTokenStatus.OK, NOW, null, 0)).when(cloudflareTokenCheck).check();
        assertThat(reconciler.reconcile()).isPresent();
    }

    @Test
    void aReconcileNeverRunsTwiceAtOnce() throws Exception {
        final var entered = new CountDownLatch(1);
        final var released = new CountDownLatch(1);
        CLOUDFLARE.blockTheNext(Call.LIST, entered, released);
        final var first = Thread.ofPlatform().start(reconciler::reconcile);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(reconciler.reconcile()).isEmpty();
        reconciler.catchUpAtStartup();

        released.countDown();
        first.join(10_000);
        assertThat(rowCount()).isOne();
        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage).contains("Custom domain reconcile skipped: a run is still going");
    }

    @Test
    void noDatabaseConnectionIsHeldDuringACloudflareCall() {
        CLOUDFLARE.store("stranger.example.net", "active", "active");
        final var missing = domain("go.example.com", CustomDomainStatus.ACTIVE, "cf-404");
        final var withoutTransaction = new TransactionTemplate(transactionManager);
        withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        CLOUDFLARE.watchConnections(() -> ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections());

        withoutTransaction.executeWithoutResult(status -> reconciler.catchUpAtStartup());

        assertThat(CLOUDFLARE.calls()).contains("LIST 1", "QUOTA ", "GET cf-404");
        assertThat(CLOUDFLARE.callsHoldingAConnection()).isEmpty();
        assertThat(statusOf(missing)).isEqualTo("ERROR");
    }

    @Test
    void theStartupCatchUpAndTheNightlyRunHandOffToTheBackground() throws NoSuchMethodException {
        final var catchUp = CustomDomainReconciler.class.getMethod("catchUpAtStartup");
        final var inBackground = CustomDomainReconciler.class.getMethod("reconcileInBackground");

        assertThat(catchUp.getAnnotation(EventListener.class).value()).containsExactly(ApplicationReadyEvent.class);
        assertThat(catchUp.getAnnotation(Async.class).value()).isEqualTo(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR);
        assertThat(inBackground.getAnnotation(Async.class).value()).isEqualTo(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR);
    }

    private static List<CloudflareHostname> hostnames(final int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> new CloudflareHostname("cf-" + index, "h" + index + ".example.com", "active", "active", List.of(), List.of()))
                .toList();
    }

    private long domain(final String hostname, final CustomDomainStatus status, final String cloudflareId) {
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, cloudflare_id, reviewed_at, next_check_at,
                                           cloudflare_delete_pending, created_at, updated_at)
                values (?, ?, ?, ?, 'Menu links for my cafe', ?, ?, ?, false, ?, ?)""",
                ANA, hostname, hostname, status.name(), cloudflareId, NOW.minusDays(2), NOW.plusHours(5), NOW.minusDays(3), NOW.minusDays(3));
        return jdbcTemplate.queryForObject("select id from custom_domain where hostname = ?", Long.class, hostname);
    }

    private void set(final long id, final String assignments) {
        jdbcTemplate.update("update custom_domain set " + assignments + " where id = ?", id);
    }

    private String statusOf(final long id) {
        return jdbcTemplate.queryForObject("select status from custom_domain where id = ?", String.class, id);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("select count(*) from custom_domain_reconcile", Integer.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, CustomDomainRepository.class}))
    @Import({
            AppConfiguration.class,
            CustomDomainReconcileRepository.class,
            CustomDomainLifecycle.class,
            CustomDomainProvisioner.class,
            CustomDomainHostnames.class,
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

        }

    }

}
