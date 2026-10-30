package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.domain.AdminDomainResponse;
import cc.hrva.urlshortener.dto.domain.DnsRecord;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainCapLock;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.CustomDomainAdminService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck.TokenState;
import cc.hrva.urlshortener.service.impl.DefaultCustomDomainAdminService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = CustomDomainApprovalRaceTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:custom-domain-approval-race;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=https://app.hrva.cc",
                "app.server-url=https://hrva.cc",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.mcp.signing-key=unused-in-this-test",
                "app.domains.max-active=1"
        })
class CustomDomainApprovalRaceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-29T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final String ADMIN = "admin@hrva.cc";
    private static final String CAP_REACHED = "Cap reached: 1 of 1 custom domains are in use. Remove one or raise the cap (APP_DOMAINS_MAX_ACTIVE).";
    private static final String FREE_ALLOWANCE_USED =
            "Cloudflare already has 100 custom hostnames and 100 are free. Delete one here or in Cloudflare first.";
    private static final long ANA = 800L;
    private static final long BOB = 801L;

    @Autowired
    private CustomDomainAdminService customDomainAdminService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private TestApplication.CommittedChanges committedChanges;

    @MockitoSpyBean
    private CustomDomainRepository customDomainRepository;

    @MockitoBean
    private CloudflareTokenCheck cloudflareTokenCheck;

    @MockitoBean
    private CustomDomainProvisioner customDomainProvisioner;

    @MockitoBean
    private CustomDomainLifecycle customDomainLifecycle;

    @MockitoBean
    private CustomDomainReconciler customDomainReconciler;

    @MockitoBean
    private ScammerSignalService scammerSignalService;

    @MockitoBean
    private CustomDomainModeration customDomainModeration;

    @MockitoBean
    private CustomDomainRemoval customDomainRemoval;

    @MockitoBean
    private LinkCheckService linkCheckService;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @BeforeEach
    void setUp() {
        account(ANA, "ana@hrva.cc");
        account(BOB, "bob@hrva.cc");
        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW, null, 0));
        committedChanges.clear();
    }

    @AfterEach
    void tearDown() {
        appProperties.getDomains().setMaxActive(1);
        jdbcTemplate.update("delete from custom_domain");
        jdbcTemplate.update("delete from user_account where id in (?, ?)", ANA, BOB);
    }

    @Test
    void twoApprovalsCannotTakeTheLastSlot() throws Exception {
        final var first = domain(ANA, "one.example.com", "PENDING_REVIEW", null, false);
        final var second = domain(BOB, "two.example.com", "PENDING_REVIEW", null, false);
        final var bothChecked = new CyclicBarrier(2);
        when(cloudflareTokenCheck.check()).thenAnswer(invocation -> {
            bothChecked.await(10, TimeUnit.SECONDS);
            return new TokenState(CloudflareTokenStatus.OK, NOW, null, 0);
        });
        final var secondArrivedWhileTheFirstHeldTheLock = bothCountTheSlots();

        final var executor = Executors.newFixedThreadPool(2);
        try {
            final var approvals = List.of(
                    CompletableFuture.supplyAsync(() -> approved(first), executor),
                    CompletableFuture.supplyAsync(() -> approved(second), executor));
            final var outcomes = approvals.stream().map(approval -> approval.handle((response, failure) -> failure).join()).toList();

            assertThat(outcomes).filteredOn(failure -> failure == null).hasSize(1);
            assertThat(outcomes).filteredOn(failure -> failure != null).singleElement()
                    .satisfies(failure -> assertThat(rootOf(failure)).isInstanceOf(CustomDomainConflictException.class).hasMessage(CAP_REACHED));
        } finally {
            executor.shutdownNow();
        }
        assertThat(List.of(statusOf(first), statusOf(second))).containsExactlyInAnyOrder("APPROVED_PROVISIONING", "PENDING_REVIEW");
        assertThat(secondArrivedWhileTheFirstHeldTheLock.get()).isFalse();
    }

    @Test
    void twoApprovalsThatBothProbed99LetAtMostOneThrough() {
        appProperties.getDomains().setMaxActive(10);
        final var first = domain(ANA, "one.example.com", "PENDING_REVIEW", null, false);
        final var second = domain(BOB, "two.example.com", "PENDING_REVIEW", null, false);
        final var bothChecked = new CyclicBarrier(2);
        when(cloudflareTokenCheck.check()).thenAnswer(invocation -> {
            bothChecked.await(10, TimeUnit.SECONDS);
            return new TokenState(CloudflareTokenStatus.OK, NOW, null, 99);
        });

        final var executor = Executors.newFixedThreadPool(2);
        try {
            final var approvals = List.of(
                    CompletableFuture.supplyAsync(() -> approved(first), executor),
                    CompletableFuture.supplyAsync(() -> approved(second), executor));
            final var outcomes = approvals.stream().map(approval -> approval.handle((response, failure) -> failure).join()).toList();

            assertThat(outcomes).filteredOn(failure -> failure == null).hasSize(1);
            assertThat(outcomes).filteredOn(failure -> failure != null).singleElement()
                    .satisfies(failure -> assertThat(rootOf(failure)).isInstanceOf(CustomDomainConflictException.class).hasMessage(FREE_ALLOWANCE_USED));
        } finally {
            executor.shutdownNow();
        }
        assertThat(List.of(statusOf(first), statusOf(second))).containsExactlyInAnyOrder("APPROVED_PROVISIONING", "PENDING_REVIEW");
    }

    @Test
    void anApprovalNotYetInCloudflareCountsTowardTheFreeAllowance() {
        appProperties.getDomains().setMaxActive(10);
        domain(ANA, "waiting.example.com", "APPROVED_PROVISIONING", null, false);
        final var pending = domain(BOB, "go.example.com", "PENDING_REVIEW", null, false);
        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW, null, 99));

        assertThatThrownBy(() -> customDomainAdminService.approve(pending, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage(FREE_ALLOWANCE_USED);
        assertThat(statusOf(pending)).isEqualTo("PENDING_REVIEW");
        verify(customDomainProvisioner, never()).provisionAsync(anyLong());
    }

    @Test
    void aReviewedApplicationIsRefusedEvenWhileCloudflareIsDown() {
        final var reviewed = domain(ANA, "go.example.com", "REJECTED", null, false);
        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.UNREACHABLE, NOW,
                "Cloudflare couldn't be reached. We'll check again.", null));

        assertThatThrownBy(() -> customDomainAdminService.approve(reviewed, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("This application was already reviewed.");
        assertThatThrownBy(() -> customDomainAdminService.approve(987_654L, null, ADMIN))
                .isInstanceOf(CustomDomainNotFoundException.class)
                .hasMessage("Custom domain not found");
        verify(cloudflareTokenCheck, never()).check();
    }

    @Test
    void slotsCountEveryHostnameInCloudflare() {
        appProperties.getDomains().setMaxActive(3);
        domain(ANA, "off.example.com", "DISABLED", "b0361ed9-0000-4000-8000-000000000001", false);
        domain(ANA, "broken.example.com", "ERROR", "b0361ed9-0000-4000-8000-000000000002", false);
        domain(ANA, "leaving.example.com", "DELETED", "b0361ed9-0000-4000-8000-000000000003", true);
        domain(ANA, "gone.example.com", "DELETED", "b0361ed9-0000-4000-8000-000000000004", false);
        domain(ANA, "never.example.com", "ERROR", null, false);
        final var pending = domain(BOB, "go.example.com", "PENDING_REVIEW", null, false);

        assertThatThrownBy(() -> customDomainAdminService.approve(pending, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("Cap reached: 3 of 3 custom domains are in use. Remove one or raise the cap (APP_DOMAINS_MAX_ACTIVE).");
        assertThat(statusOf(pending)).isEqualTo("PENDING_REVIEW");
        verify(customDomainProvisioner, never()).provisionAsync(anyLong());

        appProperties.getDomains().setMaxActive(4);

        assertThat(customDomainAdminService.approve(pending, null, ADMIN).domain().status()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
    }

    @Test
    void provisioningStartsOnlyOnceTheApprovalIsCommitted() {
        final var pending = domain(ANA, "go.example.com", "PENDING_REVIEW", null, false);
        final var seenFromOutside = new AtomicReference<String>();
        final var inATransaction = new AtomicReference<Boolean>();
        doAnswer(invocation -> {
            inATransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            seenFromOutside.set(statusOf(pending));
            return null;
        }).when(customDomainProvisioner).provisionAsync(pending);

        final var response = customDomainAdminService.approve(pending, "Looks fine", ADMIN);

        assertThat(response.domain().status()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
        assertThat(response.domain().reviewNote()).isEqualTo("Looks fine");
        assertThat(response.owner().email()).isEqualTo("ana@hrva.cc");
        assertThat(seenFromOutside.get()).isEqualTo("APPROVED_PROVISIONING");
        assertThat(inATransaction.get()).isFalse();
        final var row = jdbcTemplate.queryForMap("select reviewed_by, reviewed_at, next_check_at, review_note from custom_domain where id = ?", pending);
        assertThat(row.get("reviewed_by")).isEqualTo(ADMIN);
        assertThat(row.get("review_note")).isEqualTo("Looks fine");
        assertThat(row.get("reviewed_at")).isNotNull().isEqualTo(row.get("next_check_at"));
    }

    @Test
    void aRefusedApprovalStartsNothing() {
        final var pending = domain(ANA, "go.example.com", "PENDING_REVIEW", null, false);
        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW, null, 100));

        assertThatThrownBy(() -> customDomainAdminService.approve(pending, null, ADMIN)).isInstanceOf(CustomDomainConflictException.class);

        assertThat(statusOf(pending)).isEqualTo("PENDING_REVIEW");
        verify(customDomainProvisioner, never()).provisionAsync(anyLong());
    }

    @Test
    void approvalsAndRejectionsAnnounceTheChangeOnceCommitted() {
        appProperties.getDomains().setMaxActive(2);
        final var approving = domain(ANA, "go.example.com", "PENDING_REVIEW", null, false);
        final var rejecting = domain(BOB, "links.example.org", "PENDING_REVIEW", null, false);

        customDomainAdminService.approve(approving, null, ADMIN);
        customDomainAdminService.reject(rejecting, "Not yours", ADMIN);

        assertThat(committedChanges.changes()).containsExactly(new CustomDomainChangedEvent(approving), new CustomDomainChangedEvent(rejecting));
        assertThat(jdbcTemplate.queryForMap("select status, hostname_key, review_note from custom_domain where id = ?", rejecting))
                .containsEntry("status", "REJECTED").containsEntry("hostname_key", null).containsEntry("review_note", "Not yours");
        verify(sendingEmailService).sendDomainApprovedEmail("ana@hrva.cc", "go.example.com", DnsRecord.cname("go.example.com", "domains.hrva.cc"));
        verify(sendingEmailService).sendDomainRejectedEmail("bob@hrva.cc", "links.example.org", "Not yours");
    }

    @Test
    void aRefusedReviewAnnouncesNothing() {
        final var reviewed = domain(ANA, "go.example.com", "ACTIVE", null, false);

        assertThatThrownBy(() -> customDomainAdminService.approve(reviewed, null, ADMIN)).isInstanceOf(CustomDomainConflictException.class);
        assertThatThrownBy(() -> customDomainAdminService.reject(reviewed, null, ADMIN)).isInstanceOf(CustomDomainConflictException.class);

        assertThat(committedChanges.changes()).isEmpty();
        verifyNoInteractions(sendingEmailService);
    }

    private AtomicReference<Boolean> bothCountTheSlots() {
        final var arrived = new CountDownLatch(2);
        final var secondArrivedInTime = new AtomicReference<Boolean>();
        doAnswer(invocation -> {
            final var slots = mockingDetails(customDomainRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            arrived.countDown();
            if (secondArrivedInTime.compareAndSet(null, false)) {
                secondArrivedInTime.set(arrived.await(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS));
            }
            return slots;
        }).when(customDomainRepository).countSlotsInUse();
        return secondArrivedInTime;
    }

    private AdminDomainResponse approved(final long id) {
        return customDomainAdminService.approve(id, null, ADMIN);
    }

    private static Throwable rootOf(final Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private long domain(final long ownerId, final String hostname, final String status, final String cloudflareId, final boolean deletePending) {
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, cloudflare_id, cloudflare_delete_pending, created_at, updated_at)
                values (?, ?, ?, ?, 'Menu links for my cafe', ?, ?, ?, ?)""",
                ownerId, hostname, hostname, status, cloudflareId, deletePending, NOW.minusDays(1), NOW.minusDays(1));
        return jdbcTemplate.queryForObject("select id from custom_domain where hostname = ?", Long.class, hostname);
    }

    private String statusOf(final long id) {
        return jdbcTemplate.queryForObject("select status from custom_domain where id = ?", String.class, id);
    }

    private void account(final long id, final String email) {
        jdbcTemplate.update("insert into user_account (id, email, password, active, create_date) values (?, ?, 'secret-hash', true, ?)", id, email,
                NOW.minusDays(30));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, CustomDomainRepository.class, CustomDomainAllowanceRepository.class}))
    @Import({
            AppConfiguration.class,
            DefaultCustomDomainAdminService.class,
            AdminDomainResponses.class,
            CustomDomainResponses.class,
            CustomDomainCapLock.class,
            CustomDomainReconcileRepository.class,
            CustomDomainSetup.class,
            TestApplication.FixedClock.class,
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
        static class FixedClock {

            @Bean
            Clock clock() {
                return Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
            }

        }

    }

}
