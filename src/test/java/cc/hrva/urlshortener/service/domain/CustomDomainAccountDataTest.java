package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.client.CloudflareResult;
import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.converter.UserUpdateDtoToUserConverter;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.ResetTokenRepository;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.VerificationTokenRepository;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.service.ResetTokenService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.VerificationTokenService;
import cc.hrva.urlshortener.service.bio.BioAccountData;
import cc.hrva.urlshortener.service.domain.CustomDomainLifecycle.LifecycleRun;
import cc.hrva.urlshortener.service.domain.FakeCloudflare.Call;
import cc.hrva.urlshortener.service.impl.DefaultSendingEmailService;
import cc.hrva.urlshortener.service.impl.DefaultUserService;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.notification.NotificationAccountData;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = CustomDomainAccountDataTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.jpa.open-in-view=false",
                "spring.datasource.url=jdbc:h2:mem:custom-domain-account-data;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
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
class CustomDomainAccountDataTest {

    private static final Instant START = Instant.parse("2026-10-29T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(START, ZoneOffset.UTC);
    private static final long ANA = 930L;
    private static final long BOB = 931L;
    private static final String ANA_EMAIL = "ana@hrva.cc";
    private static final MutableClock CLOCK = new MutableClock(START);
    private static final FakeCloudflare CLOUDFLARE = new FakeCloudflare();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomDomainRepository customDomainRepository;

    @Autowired
    private CustomDomainLifecycle lifecycle;

    @Autowired
    private CustomDomainRegistry registry;

    @Autowired
    private AccountLock accountLock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestApplication.CommittedChanges committedChanges;

    @MockitoBean
    private Reauthentication reauthentication;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private ApiKeyRepository apiKeyRepository;

    @MockitoBean
    private EmailLogRepository emailLogRepository;

    @MockitoBean
    private ResetTokenService resetTokenService;

    @MockitoBean
    private ResetTokenRepository resetTokenRepository;

    @MockitoBean
    private TwoFactorRecoveryCodeRepository twoFactorRecoveryCodeRepository;

    @MockitoBean
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @MockitoBean
    private BioAccountData bioAccountData;

    @MockitoBean
    private NotificationAccountData notificationAccountData;

    @MockitoBean
    private DefaultSendingEmailService sendingEmailService;

    @MockitoBean
    private VerificationTokenService verificationTokenService;

    @MockitoBean
    private VerificationTokenRepository verificationTokenRepository;

    @MockitoBean
    private ConnectedAppsService connectedAppsService;

    @MockitoBean
    private ScammerSignalService scammerSignalService;

    @BeforeEach
    void setUp() {
        CLOCK.set(START);
        CLOUDFLARE.reset();
        committedChanges.clear();
        user(ANA, ANA_EMAIL);
        user(BOB, "bob@hrva.cc");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        assertThat(CLOUDFLARE.callsInsideATransaction()).isEmpty();
        jdbcTemplate.update("delete from url");
        jdbcTemplate.update("delete from custom_domain");
        jdbcTemplate.update("delete from custom_domain_allowance");
        jdbcTemplate.update("delete from api_key where owner_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from user_account_authorities where user_id in (?, ?)", ANA, BOB);
        jdbcTemplate.update("delete from user_account where id in (?, ?)", ANA, BOB);
        registry.reload();
    }

    @Test
    void exportListsEveryDomainWithoutInternals() {
        final var active = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE, "cf-secret-1", NOW.minusDays(10));
        set(active, "reviewed_by = 'reviewer@hrva.cc', review_note = 'Looks fine', reviewed_at = ?, activated_at = ?, "
                + "apply_flags = 'Registered 3 days ago', last_error = 'Cloudflare said no', cloudflare_status = 'active', "
                + "cloudflare_ssl_status = 'active'", NOW.minusDays(9), NOW.minusDays(8));
        final var removed = domain(ANA, "old.example.com", CustomDomainStatus.DELETED, "cf-secret-2", NOW.minusDays(20));
        set(removed, "hostname_key = null, reviewed_at = ?, activated_at = ?, disabled_at = ?, deleted_at = ?", NOW.minusDays(19),
                NOW.minusDays(18), NOW.minusDays(17), NOW.minusDays(16));
        final var rejected = domain(ANA, "no.example.com", CustomDomainStatus.REJECTED, null, NOW.minusDays(30));
        set(rejected, "hostname_key = null, review_note = 'Not enough detail', reviewed_at = ?", NOW.minusDays(29));
        domain(BOB, "bob.example.com", CustomDomainStatus.ACTIVE, "cf-secret-3", NOW.minusDays(1));
        signedIn();

        final var json = JSON.writeValueAsString(userService.exportMyData());

        assertThat(JSON.readTree(json).get("domains").toString()).isEqualTo("""
                [{"hostname":"go.example.com","status":"ACTIVE","reason":"Menu links for my cafe","reviewNote":"Looks fine",\
                "createdAt":"2026-10-19T09:00:00","reviewedAt":"2026-10-20T09:00:00","activatedAt":"2026-10-21T09:00:00",\
                "disabledAt":null,"deletedAt":null},\
                {"hostname":"old.example.com","status":"DELETED","reason":"Menu links for my cafe","reviewNote":null,\
                "createdAt":"2026-10-09T09:00:00","reviewedAt":"2026-10-10T09:00:00","activatedAt":"2026-10-11T09:00:00",\
                "disabledAt":"2026-10-12T09:00:00","deletedAt":"2026-10-13T09:00:00"},\
                {"hostname":"no.example.com","status":"REJECTED","reason":"Menu links for my cafe","reviewNote":"Not enough detail",\
                "createdAt":"2026-09-29T09:00:00","reviewedAt":"2026-09-30T09:00:00","activatedAt":null,\
                "disabledAt":null,"deletedAt":null}]""");
        assertThat(json)
                .doesNotContain("\"cloudflareId\"", "\"lastError\"", "\"applyFlags\"", "\"reviewedBy\"", "\"cloudflareStatus\"")
                .doesNotContain("cf-secret-1", "cf-secret-2", "Cloudflare said no", "Registered 3 days ago", "reviewer@hrva.cc")
                .doesNotContain("bob.example.com");
    }

    @Test
    void deletionQueuesTheCloudflareDeleteAndLeavesNoOwner() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "active", "active");
        final var active = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE, cloudflareId, NOW.minusDays(10));
        link(ANA, active, "menu", true);
        link(ANA, active, "drinks", false);
        link(ANA, null, "ana-blog", true);
        jdbcTemplate.update("insert into custom_domain_allowance (user_id, max_domains, reason, granted_by, granted_at) values (?, 3, 'Agency', "
                + "'admin@localhost', ?)", ANA, NOW.minusDays(5));
        jdbcTemplate.update("insert into api_key (id, \"KEY\", owner_id, api_calls_used, active) values (9301, 'ana-key', ?, 0, true)", ANA);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, 1)", ANA);
        registry.reload();
        assertThat(registry.active("go.example.com")).isPresent();

        userService.deleteUserById(ANA);

        assertThat(count("select count(*) from url")).isZero();
        assertThat(row(active))
                .containsEntry("STATUS", "DELETED")
                .containsEntry("OWNER_ID", null)
                .containsEntry("CLOUDFLARE_DELETE_PENDING", true)
                .containsEntry("HOSTNAME_KEY", "go.example.com")
                .containsEntry("DELETED_AT", Timestamp.valueOf(NOW))
                .containsEntry("NEXT_CHECK_AT", null);
        assertThat(CLOUDFLARE.calls()).isEmpty();
        assertThat(count("select count(*) from user_account where id = " + ANA)).isZero();
        assertThat(count("select count(*) from custom_domain_allowance")).isZero();
        assertThat(count("select count(*) from api_key")).isZero();
        assertThat(count("select count(*) from user_account_authorities where user_id = " + ANA)).isZero();
        assertThat(committedChanges.changes()).containsExactly(new CustomDomainChangedEvent(active));
        assertThat(registry.route("go.example.com")).isEmpty();
    }

    @Test
    void pendingAndRejectedApplicationsAreDeletedOutright() {
        domain(ANA, "new.example.com", CustomDomainStatus.PENDING_REVIEW, null, NOW.minusDays(2));
        final var rejected = domain(ANA, "no.example.com", CustomDomainStatus.REJECTED, null, NOW.minusDays(3));
        set(rejected, "hostname_key = null, review_note = 'Not enough detail', reviewed_at = ?", NOW.minusDays(2));
        final var bobs = domain(BOB, "bob.example.com", CustomDomainStatus.PENDING_REVIEW, null, NOW.minusDays(1));

        userService.deleteUserById(ANA);

        assertThat(jdbcTemplate.queryForList("select id from custom_domain", Long.class)).containsExactly(bobs);
        assertThat(customDomainRepository.findByHostnameKey("new.example.com")).isEmpty();
        assertThat(committedChanges.changes()).isEmpty();
    }

    @Test
    void domainsNeverSetUpInCloudflareAreDeletedOnceTheirLinksAreGone() {
        final var settingUp = domain(ANA, "new.example.com", CustomDomainStatus.APPROVED_PROVISIONING, null, NOW.minusDays(2));
        final var failed = domain(ANA, "failed.example.com", CustomDomainStatus.ERROR, null, NOW.minusDays(3));
        set(failed, "problem = 'PROVISIONING_FAILED'");
        final var turnedOff = domain(ANA, "off.example.com", CustomDomainStatus.DISABLED, null, NOW.minusDays(4));
        set(turnedOff, "disabled_reason = 'ADMIN', disabled_note = 'Phishing', disabled_at = ?", NOW.minusDays(1));
        final var removed = domain(ANA, "old.example.com", CustomDomainStatus.DELETED, null, NOW.minusDays(9));
        set(removed, "hostname_key = null, deleted_at = ?", NOW.minusDays(3));
        link(ANA, removed, "menu", false);

        userService.deleteUserById(ANA);

        assertThat(count("select count(*) from custom_domain")).isZero();
        assertThat(count("select count(*) from url")).isZero();
        assertThat(CLOUDFLARE.calls()).isEmpty();
        assertThat(committedChanges.changes()).extracting(CustomDomainChangedEvent::domainId)
                .containsExactlyInAnyOrder(settingUp, failed, turnedOff, removed);
    }

    @Test
    void anEarlierRemovalKeepsItsQueuedDeleteAndAFinishedOneIsNotQueuedAgain() {
        final var queued = domain(ANA, "queued.example.com", CustomDomainStatus.DELETED, CLOUDFLARE.store("queued.example.com", "active", "active"),
                NOW.minusDays(10));
        set(queued, "cloudflare_delete_pending = true, deleted_at = ?", NOW.minusDays(1));
        final var finished = domain(ANA, "finished.example.com", CustomDomainStatus.DELETED, "cf-gone", NOW.minusDays(20));
        set(finished, "hostname_key = null, deleted_at = ?", NOW.minusDays(2));
        link(ANA, finished, "menu", false);

        userService.deleteUserById(ANA);

        assertThat(row(queued))
                .containsEntry("OWNER_ID", null)
                .containsEntry("STATUS", "DELETED")
                .containsEntry("CLOUDFLARE_DELETE_PENDING", true)
                .containsEntry("HOSTNAME_KEY", "queued.example.com")
                .containsEntry("DELETED_AT", Timestamp.valueOf(NOW.minusDays(1)));
        assertThat(count("select count(*) from custom_domain where id = " + finished)).isZero();

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 1, 0));

        assertThat(CLOUDFLARE.calls()).containsExactly("DELETE cf-1");
        assertThat(count("select count(*) from custom_domain")).isZero();
    }

    @Test
    void aDeletedAccountLeavesNoDomainRowOnceCloudflareIsDone() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "active", "active");
        final var active = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE, cloudflareId, NOW.minusDays(10));
        link(ANA, active, "menu", true);
        CLOUDFLARE.watchConnections(() -> ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections());

        userService.deleteUserById(ANA);
        CLOUDFLARE.script(Call.DELETE, new CloudflareResult.Unreachable<>("timeout"));

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 0, 1));
        assertThat(row(active)).containsEntry("OWNER_ID", null).containsEntry("CLOUDFLARE_DELETE_PENDING", true).containsEntry("STATUS", "DELETED");
        assertThat(CLOUDFLARE.has(cloudflareId)).isTrue();

        assertThat(lifecycle.runOnce()).isEqualTo(new LifecycleRun(0, 0, 1, 0));

        assertThat(count("select count(*) from custom_domain")).isZero();
        assertThat(CLOUDFLARE.has(cloudflareId)).isFalse();
        assertThat(customDomainRepository.findByHostnameKey("go.example.com")).isEmpty();
        assertThat(CLOUDFLARE.calls()).containsExactly("DELETE " + cloudflareId, "DELETE " + cloudflareId);
        assertThat(CLOUDFLARE.callsHoldingAConnection()).isEmpty();
    }

    @Test
    void anAccountDeletedWhileItsDomainIsSetUpLeavesNoHostname() {
        final var settingUp = domain(ANA, "go.example.com", CustomDomainStatus.APPROVED_PROVISIONING, null, NOW.minusDays(1));
        set(settingUp, "reviewed_at = ?, next_check_at = ?", NOW.minusMinutes(20), NOW);
        CLOUDFLARE.duringTheNextCreate(() -> userService.deleteUserById(ANA));

        assertThat(lifecycle.runOnce().provisioned()).isZero();

        assertThat(CLOUDFLARE.calls()).containsExactly("FIND go.example.com", "TOTAL ", "CREATE go.example.com", "DELETE cf-1");
        assertThat(CLOUDFLARE.has("cf-1")).isFalse();
        assertThat(count("select count(*) from custom_domain")).isZero();
    }

    @Test
    void aLinkCreatedOnTheDomainWhileTheAccountIsDeletedIsDeletedWithIt() throws Exception {
        final var active = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE, "cf-1", NOW.minusDays(10));
        link(ANA, active, "menu", true);
        final var creatorHoldsTheRow = new CountDownLatch(1);
        final var commitTheLink = new CountDownLatch(1);
        final var creator = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(customDomainRepository.findStatusForUpdate(active)).contains("ACTIVE");
            jdbcTemplate.update("insert into url (short_url, long_url, owner_id, domain_id, active) values ('late', 'https://example.org/late', ?, ?, true)",
                    ANA, active);
            creatorHoldsTheRow.countDown();
            awaitQuietly(commitTheLink);
        }));
        assertThat(creatorHoldsTheRow.await(10, TimeUnit.SECONDS)).isTrue();
        final var deletion = CompletableFuture.runAsync(() -> userService.deleteUserById(ANA));

        assertThatThrownBy(() -> deletion.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        commitTheLink.countDown();
        creator.get(10, TimeUnit.SECONDS);
        deletion.get(10, TimeUnit.SECONDS);

        assertThat(count("select count(*) from url")).isZero();
        assertThat(row(active)).containsEntry("STATUS", "DELETED").containsEntry("OWNER_ID", null).containsEntry("CLOUDFLARE_DELETE_PENDING", true);
    }

    @Test
    void aLinkCreateThatWaitedForTheDeletionFindsTheDomainRemoved() throws Exception {
        final var active = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE, "cf-1", NOW.minusDays(10));
        final var seenByTheRead = new AtomicReference<String>();
        final var creator = new AtomicReference<CompletableFuture<Optional<String>>>();
        when(connectedAppsService.disconnectAll(ANA_EMAIL)).thenAnswer(invocation -> {
            final var readTheDomain = new CountDownLatch(1);
            creator.set(CompletableFuture.supplyAsync(() -> new TransactionTemplate(transactionManager).execute(status -> {
                seenByTheRead.set(customDomainRepository.findUsable("go.example.com", ANA).map(found -> found.getStatus().name()).orElse(null));
                readTheDomain.countDown();
                return customDomainRepository.findStatusForUpdate(active);
            })));
            assertThat(readTheDomain.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> creator.get().get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            return 0;
        });

        userService.deleteUserById(ANA);

        assertThat(seenByTheRead.get()).isEqualTo("ACTIVE");
        assertThat(creator.get().get(10, TimeUnit.SECONDS)).contains("DELETED");
    }

    @Test
    void anApplicationThatWaitedForTheDeletionFindsNoAccount() {
        final var application = new AtomicReference<CompletableFuture<User>>();
        when(connectedAppsService.disconnectAll(ANA_EMAIL)).thenAnswer(invocation -> {
            final var started = new CountDownLatch(1);
            application.set(CompletableFuture.supplyAsync(() -> new TransactionTemplate(transactionManager).execute(status -> {
                started.countDown();
                return accountLock.lock(User.builder().id(ANA).build());
            })));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> application.get().get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            return 0;
        });

        userService.deleteUserById(ANA);

        assertThatThrownBy(() -> application.get().get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(NoAuthorizationException.class);
    }

    @Test
    void deactivationKeepsEverything() {
        final var cloudflareId = CLOUDFLARE.store("go.example.com", "active", "active");
        final var active = domain(ANA, "go.example.com", CustomDomainStatus.ACTIVE, cloudflareId, NOW.minusDays(10));
        final var menu = link(ANA, active, "menu", true);
        registry.reload();
        final var domainBefore = row(active);
        final var linkBefore = linkRow(menu);

        userService.deactivate(userRepository.findById(ANA).orElseThrow());
        registry.reload();

        assertThat(row(active)).isEqualTo(domainBefore);
        assertThat(linkRow(menu)).isEqualTo(linkBefore);
        assertThat(registry.route("go.example.com")).hasValueSatisfying(route -> {
            assertThat(route.status()).isEqualTo(CustomDomainStatus.ACTIVE);
            assertThat(route.ownerActive()).isFalse();
        });
        assertThat(registry.active("go.example.com")).isEmpty();
        assertThat(customDomainRepository.findUsable("go.example.com", ANA)).isEmpty();

        assertThat(userService.reactivate(ANA)).isTrue();
        registry.reload();

        assertThat(registry.active("go.example.com")).hasValueSatisfying(route -> assertThat(route.id()).isEqualTo(active));
        assertThat(row(active)).isEqualTo(domainBefore);
        assertThat(linkRow(menu)).isEqualTo(linkBefore);
        assertThat(CLOUDFLARE.calls()).isEmpty();
        assertThat(committedChanges.changes()).isEmpty();
    }

    private void signedIn() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ANA_EMAIL, null, List.of()));
    }

    private void user(final long id, final String email) {
        jdbcTemplate.update("insert into user_account (id, email, password, active, create_date) values (?, ?, 'secret-hash', true, ?)", id, email,
                NOW.minusDays(60));
    }

    private long domain(final long ownerId, final String hostname, final CustomDomainStatus status, final String cloudflareId,
                        final LocalDateTime createdAt) {
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, cloudflare_id, next_check_at,
                                           cloudflare_delete_pending, created_at, updated_at)
                values (?, ?, ?, ?, 'Menu links for my cafe', ?, ?, false, ?, ?)""",
                ownerId, hostname, hostname, status.name(), cloudflareId, status == CustomDomainStatus.ACTIVE ? NOW.plusHours(1) : null, createdAt,
                createdAt);
        return jdbcTemplate.queryForObject("select id from custom_domain where hostname = ?", Long.class, hostname);
    }

    private long link(final long ownerId, final Long domainId, final String alias, final boolean active) {
        jdbcTemplate.update("insert into url (short_url, long_url, owner_id, domain_id, active) values (?, ?, ?, ?, ?)",
                alias, "https://example.org/" + alias, ownerId, domainId, active);
        return jdbcTemplate.queryForObject("select id from url where short_url = ?", Long.class, alias);
    }

    private void set(final long id, final String assignments, final Object... values) {
        final var arguments = new ArrayList<>(List.of(values));
        arguments.add(id);
        jdbcTemplate.update("update custom_domain set " + assignments + " where id = ?", arguments.toArray());
    }

    private Map<String, Object> row(final long id) {
        return jdbcTemplate.queryForMap("select * from custom_domain where id = ?", id);
    }

    private Map<String, Object> linkRow(final long id) {
        return jdbcTemplate.queryForMap("select * from url where id = ?", id);
    }

    private int count(final String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, UrlRepository.class, CustomDomainRepository.class, CustomDomainAllowanceRepository.class}))
    @Import({
            AppConfiguration.class,
            AccountLock.class,
            DefaultUserService.class,
            UserToUserDtoConverter.class,
            UserUpdateDtoToUserConverter.class,
            CustomDomainAccountData.class,
            CustomDomainRegistry.class,
            CustomDomainLifecycle.class,
            CustomDomainProvisioner.class,
            CustomDomainHostnames.class,
            TestApplication.Fixed.class,
            TestApplication.CommittedChanges.class
    })
    static class TestApplication {

        static class CommittedChanges {

            private final List<CustomDomainChangedEvent> changes = new CopyOnWriteArrayList<>();

            @TransactionalEventListener
            public void onChange(final CustomDomainChangedEvent change) {
                changes.add(change);
            }

            List<CustomDomainChangedEvent> changes() {
                return List.copyOf(changes);
            }

            void clear() {
                changes.clear();
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
