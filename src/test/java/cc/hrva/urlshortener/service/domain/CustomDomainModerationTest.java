package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.service.DomainReputationService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainDisabled;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.service.impl.DefaultLinkCheckService;
import cc.hrva.urlshortener.service.settings.DefaultSettingsTestConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@SpringBootTest(
        classes = CustomDomainModerationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:custom-domain-moderation;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=https://app.hrva.cc",
                "app.server-url=https://hrva.cc",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.mcp.signing-key=unused-in-this-test"
        })
class CustomDomainModerationTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-29T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final long ANA = 830L;
    private static final long BOB = 831L;
    private static final String CANT_TURN_OFF = "This domain can't be turned off now.";
    private static final String NOT_TURNED_OFF = "This domain isn't turned off.";
    private static final String ADMIN = "admin@hrva.cc";

    @Autowired
    private CustomDomainModeration customDomainModeration;

    @Autowired
    private CustomDomainRepository customDomainRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestApplication.CommittedEvents committedEvents;

    @MockitoBean
    private CustomDomainLifecycle customDomainLifecycle;

    @MockitoBean
    private BlocklistSourceRepository blocklistSourceRepository;

    @MockitoBean
    private BlocklistStore blocklistStore;

    @MockitoBean
    private DomainAgeService domainAgeService;

    @MockitoBean
    private DomainReputationService domainReputationService;

    @MockitoBean
    private SuspiciousUrlService suspiciousUrlService;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @BeforeEach
    void setUp() {
        account(ANA, "ana@hrva.cc");
        account(BOB, "bob@hrva.cc");
        committedEvents.clear();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from custom_domain");
        jdbcTemplate.update("delete from domain_rule");
        jdbcTemplate.update("delete from user_account where id in (?, ?)", ANA, BOB);
    }

    @Test
    void disableKeepsTheSlotAndStopsPolling() {
        final var id = domain(ANA, "go.example.com", "ACTIVE", "cf-1");
        final var slotsBefore = customDomainRepository.countSlotsInUse();

        customDomainModeration.disable(id, DomainDisableReason.ADMIN, "Phishing kit");

        assertThat(row(id))
                .containsEntry("STATUS", "DISABLED")
                .containsEntry("DISABLED_REASON", "ADMIN")
                .containsEntry("DISABLED_NOTE", "Phishing kit")
                .containsEntry("NEXT_CHECK_AT", null)
                .containsEntry("CLOUDFLARE_ID", "cf-1")
                .containsEntry("CLOUDFLARE_DELETE_PENDING", false);
        assertThat(jdbcTemplate.queryForObject("select disabled_at from custom_domain where id = ?", LocalDateTime.class, id)).isEqualTo(NOW);
        assertThat(customDomainRepository.countSlotsInUse()).isEqualTo(slotsBefore);
        assertThat(committedEvents.events()).containsExactly(new CustomDomainChangedEvent(id),
                new DomainDisabled(id, "go.example.com", DomainDisableReason.ADMIN, "Phishing kit"));
    }

    @Test
    void disableAppliesToLiveWaitingProvisioningAndFailedDomainsOnly() {
        for (final var status : List.of("APPROVED_PROVISIONING", "AWAITING_DNS", "ERROR")) {
            final var id = domain(ANA, status.toLowerCase(Locale.ROOT).replace('_', '-') + ".example.com", status, null);

            customDomainModeration.disable(id, DomainDisableReason.ADMIN, "Spam");

            assertThat(statusOf(id)).isEqualTo("DISABLED");
        }
        for (final var status : List.of("PENDING_REVIEW", "REJECTED", "DISABLED", "DELETED")) {
            final var id = domain(BOB, status.toLowerCase(Locale.ROOT).replace('_', '-') + ".example.org", status, null);

            assertThatThrownBy(() -> customDomainModeration.disable(id, DomainDisableReason.ADMIN, "Spam"))
                    .isInstanceOf(CustomDomainConflictException.class)
                    .hasMessage(CANT_TURN_OFF);
            assertThat(statusOf(id)).isEqualTo(status);
        }
        assertThatThrownBy(() -> customDomainModeration.disable(999_999L, DomainDisableReason.ADMIN, "Spam"))
                .isInstanceOf(CustomDomainNotFoundException.class)
                .hasMessage("Custom domain not found");
    }

    @Test
    void disablingEmailsTheOwnerWithTheReason() {
        final var byAdmin = domain(ANA, "go.example.com", "ACTIVE", "cf-1");
        final var byBlock = domain(BOB, "bob.example.org", "AWAITING_DNS", "cf-2");
        final var byRule = domain(ANA, "shop.scam-wave.com", "ACTIVE", "cf-3");
        final var byList = domain(ANA, "links.example.net", "ERROR", "cf-4");
        rule("scam-wave.com", "BLOCK");
        final var seenWhenSent = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            final String hostname = invocation.getArgument(1);
            seenWhenSent.add(hostname + " " + TransactionSynchronizationManager.isActualTransactionActive() + " "
                    + CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForObject("select status from custom_domain where hostname = ?", String.class,
                    hostname)).join());
            return null;
        }).when(sendingEmailService).sendDomainDisabledEmail(anyString(), anyString(), anyString());

        customDomainModeration.disable(byAdmin, DomainDisableReason.ADMIN, "Phishing kit");
        customDomainModeration.disableForUser(BOB, ADMIN);
        customDomainModeration.disableMatching("scam-wave.com", "Fake shops");
        customDomainModeration.disableIfStillOn(byList, DomainDisableReason.BLOCKLIST, null);

        verify(sendingEmailService).sendDomainDisabledEmail("ana@hrva.cc", "go.example.com", "Phishing kit");
        verify(sendingEmailService).sendDomainDisabledEmail("bob@hrva.cc", "bob.example.org", "Turned off because the account was blocked.");
        verify(sendingEmailService).sendDomainDisabledEmail("ana@hrva.cc", "shop.scam-wave.com", "Turned off because the domain is on a blocklist.");
        verify(sendingEmailService).sendDomainDisabledEmail("ana@hrva.cc", "links.example.net", "Turned off because the domain is on a blocklist.");
        verifyNoMoreInteractions(sendingEmailService);
        assertThat(seenWhenSent).containsExactly("go.example.com false DISABLED", "bob.example.org false DISABLED",
                "shop.scam-wave.com false DISABLED", "links.example.net false DISABLED");
        assertThat(List.of(byAdmin, byBlock, byRule, byList)).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("DISABLED"));
        assertThat(DomainDisableReason.values()).containsExactlyInAnyOrder(DomainDisableReason.ADMIN, DomainDisableReason.USER_BLOCKED,
                DomainDisableReason.DOMAIN_RULE, DomainDisableReason.BLOCKLIST);
    }

    @Test
    void aDomainThatWasNotTurnedOffEmailsNobody() {
        final var alreadyOff = domain(ANA, "off.example.com", "DISABLED", "cf-1");
        final var pending = domain(ANA, "new.example.com", "PENDING_REVIEW", null);

        assertThat(customDomainModeration.disableIfStillOn(alreadyOff, DomainDisableReason.BLOCKLIST, null)).isFalse();
        assertThatThrownBy(() -> customDomainModeration.disable(pending, DomainDisableReason.ADMIN, "Spam"))
                .isInstanceOf(CustomDomainConflictException.class);
        customDomainModeration.disableForUser(ANA, ADMIN);

        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void aFailedEmailKeepsTheDomainOff() {
        final var id = domain(ANA, "go.example.com", "ACTIVE", "cf-1");
        doThrow(new TaskRejectedException("mail queue full")).when(sendingEmailService).sendDomainDisabledEmail(anyString(), anyString(), anyString());

        customDomainModeration.disable(id, DomainDisableReason.ADMIN, "Phishing kit");

        assertThat(statusOf(id)).isEqualTo("DISABLED");
        assertThat(customDomainModeration.disableIfStillOn(domain(ANA, "links.example.com", "ACTIVE", "cf-2"), DomainDisableReason.BLOCKLIST, null))
                .isTrue();
    }

    @Test
    void enableChecksAgainAtOnce() {
        final var id = domain(ANA, "go.example.com", "DISABLED", "cf-1");
        jdbcTemplate.update("update custom_domain set disabled_reason = 'ADMIN', disabled_note = 'Spam', disabled_at = ?, problem = 'CNAME_REMOVED' "
                + "where id = ?", NOW.minusDays(1), id);
        final var seenByTheCheck = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            seenByTheCheck.add(String.valueOf(TransactionSynchronizationManager.isActualTransactionActive()));
            seenByTheCheck.add(CompletableFuture.supplyAsync(() -> statusOf(id)).join());
            return LifecycleOutcome.DONE;
        }).when(customDomainLifecycle).checkNow(id);

        customDomainModeration.enable(id);

        assertThat(row(id))
                .containsEntry("STATUS", "AWAITING_DNS")
                .containsEntry("DISABLED_REASON", null)
                .containsEntry("DISABLED_NOTE", null)
                .containsEntry("DISABLED_AT", null)
                .containsEntry("PROBLEM", null)
                .containsEntry("CLOUDFLARE_ID", "cf-1");
        assertThat(jdbcTemplate.queryForObject("select next_check_at from custom_domain where id = ?", LocalDateTime.class, id)).isEqualTo(NOW);
        assertThat(seenByTheCheck).containsExactly("false", "AWAITING_DNS");
        assertThat(committedEvents.events()).containsExactly(new CustomDomainChangedEvent(id));
    }

    @Test
    void aFailingCheckAfterEnableKeepsTheDomainDueForTheNextRun() {
        final var id = domain(ANA, "go.example.com", "DISABLED", "cf-1");
        doThrow(new IllegalStateException("Cloudflare down")).when(customDomainLifecycle).checkNow(id);

        customDomainModeration.enable(id);

        assertThat(statusOf(id)).isEqualTo("AWAITING_DNS");
        assertThat(jdbcTemplate.queryForObject("select next_check_at from custom_domain where id = ?", LocalDateTime.class, id)).isEqualTo(NOW);
    }

    @Test
    void aDomainTurnedOffBeforeItWasSetUpWaitsForARetryWhenTurnedOn() {
        final var id = domain(ANA, "go.example.com", "DISABLED", null);

        customDomainModeration.enable(id);

        assertThat(row(id))
                .containsEntry("STATUS", "ERROR")
                .containsEntry("PROBLEM", "PROVISIONING_FAILED")
                .containsEntry("LAST_ERROR", "It was turned on again before setup finished.")
                .containsEntry("NEXT_CHECK_AT", null)
                .containsEntry("DISABLED_REASON", null);
        assertThat(customDomainRepository.countSlotsInUse()).isZero();
        verify(customDomainLifecycle, never()).checkNow(anyLong());
    }

    @Test
    void enableAppliesToTurnedOffDomainsOnly() {
        for (final var status : List.of("PENDING_REVIEW", "APPROVED_PROVISIONING", "AWAITING_DNS", "ACTIVE", "REJECTED", "ERROR", "DELETED")) {
            final var id = domain(ANA, status.toLowerCase(Locale.ROOT).replace('_', '-') + ".example.com", status, "cf-" + status);

            assertThatThrownBy(() -> customDomainModeration.enable(id))
                    .isInstanceOf(CustomDomainConflictException.class)
                    .hasMessage(NOT_TURNED_OFF);
            assertThat(statusOf(id)).isEqualTo(status);
        }
        verify(customDomainLifecycle, never()).checkNow(anyLong());
    }

    @Test
    void aBlockRuleTurnsOffTheRuleDomainAndItsSubdomainsOnly() {
        final var nested = domain(ANA, "example.com.go.example.com", "ACTIVE", "cf-1");
        final var below = domain(ANA, "go.example.com", "ACTIVE", "cf-2");
        final var deeper = domain(BOB, "deep.links.example.com", "AWAITING_DNS", "cf-3");
        final var lookAlike = domain(BOB, "goexample.com.evil.org", "ACTIVE", "cf-4");
        final var otherSuffix = domain(BOB, "go.example.org", "ACTIVE", "cf-5");
        final var pending = domain(BOB, "new.example.com", "PENDING_REVIEW", null);
        final var alreadyOff = domain(BOB, "off.example.com", "DISABLED", "cf-6");
        jdbcTemplate.update("update custom_domain set disabled_reason = 'ADMIN', disabled_note = 'Spam' where id = ?", alreadyOff);

        rule("example.com", "BLOCK");

        final var disabled = customDomainModeration.disableMatching("example.com", "Scam wave");

        assertThat(disabled).isEqualTo(3);
        assertThat(statusOf(nested)).isEqualTo("DISABLED");
        assertThat(row(below)).containsEntry("STATUS", "DISABLED").containsEntry("DISABLED_REASON", "DOMAIN_RULE")
                .containsEntry("DISABLED_NOTE", "example.com: Scam wave");
        assertThat(statusOf(deeper)).isEqualTo("DISABLED");
        assertThat(statusOf(lookAlike)).isEqualTo("ACTIVE");
        assertThat(statusOf(otherSuffix)).isEqualTo("ACTIVE");
        assertThat(statusOf(pending)).isEqualTo("PENDING_REVIEW");
        assertThat(row(alreadyOff)).containsEntry("DISABLED_REASON", "ADMIN").containsEntry("DISABLED_NOTE", "Spam");
        assertThat(committedEvents.alerts()).contains(new DomainDisabled(below, "go.example.com", DomainDisableReason.DOMAIN_RULE,
                "example.com: Scam wave"));
    }

    @Test
    void aBlockRuleOnASubdomainTurnsOffThatDomain() {
        final var exact = domain(ANA, "go.example.com", "ACTIVE", "cf-1");
        final var sibling = domain(BOB, "links.example.com", "ACTIVE", "cf-2");

        rule("go.example.com", "BLOCK");

        assertThat(customDomainModeration.disableMatching("go.example.com", "Phishing")).isOne();

        assertThat(statusOf(exact)).isEqualTo("DISABLED");
        assertThat(statusOf(sibling)).isEqualTo("ACTIVE");
    }

    @Test
    void aMoreSpecificAllowRuleKeepsItsDomainOn() {
        final var trusted = domain(ANA, "go.example.com", "ACTIVE", "cf-1");
        final var underTheAllow = domain(ANA, "deep.go.example.com", "AWAITING_DNS", "cf-2");
        final var blocked = domain(BOB, "links.example.com", "ACTIVE", "cf-3");
        rule("example.com", "BLOCK");
        rule("go.example.com", "ALLOW");

        assertThat(customDomainModeration.disableMatching("example.com", "Scam wave")).isOne();

        assertThat(statusOf(trusted)).isEqualTo("ACTIVE");
        assertThat(statusOf(underTheAllow)).isEqualTo("AWAITING_DNS");
        assertThat(statusOf(blocked)).isEqualTo("DISABLED");
    }

    @Test
    void aMoreSpecificBlockRuleBelowAnAllowStillTurnsItsDomainOff() {
        final var blocked = domain(ANA, "bad.go.example.com", "ACTIVE", "cf-1");
        rule("go.example.com", "ALLOW");
        rule("bad.go.example.com", "BLOCK");

        assertThat(customDomainModeration.disableMatching("bad.go.example.com", null)).isOne();

        assertThat(statusOf(blocked)).isEqualTo("DISABLED");
    }

    @Test
    void aBlockRuleWithoutAReasonNamesTheRuleDomain() {
        final var id = domain(ANA, "go.example.com", "ACTIVE", "cf-1");

        rule("example.com", "BLOCK");

        customDomainModeration.disableMatching("example.com", "  ");

        assertThat(row(id)).containsEntry("DISABLED_NOTE", "example.com");
    }

    @Test
    void blockingAUserTurnsOffTheirDomainsAndRejectsTheirApplications() {
        final var active = domain(ANA, "go.example.com", "ACTIVE", "cf-1");
        final var waiting = domain(ANA, "wait.example.com", "AWAITING_DNS", "cf-2");
        final var pending = domain(ANA, "new.example.com", "PENDING_REVIEW", null);
        final var adminOff = domain(ANA, "off.example.com", "DISABLED", "cf-3");
        jdbcTemplate.update("update custom_domain set disabled_reason = 'ADMIN' where id = ?", adminOff);
        final var bobs = domain(BOB, "bob.example.org", "ACTIVE", "cf-4");

        final var disabled = customDomainModeration.disableForUser(ANA, ADMIN);

        assertThat(disabled).isEqualTo(2);
        assertThat(row(active)).containsEntry("STATUS", "DISABLED").containsEntry("DISABLED_REASON", "USER_BLOCKED");
        assertThat(row(waiting)).containsEntry("STATUS", "DISABLED").containsEntry("DISABLED_REASON", "USER_BLOCKED");
        assertThat(row(pending)).containsEntry("STATUS", "REJECTED").containsEntry("REVIEW_NOTE", "Account blocked")
                .containsEntry("REVIEWED_BY", ADMIN).containsEntry("HOSTNAME_KEY", null);
        assertThat(row(adminOff)).containsEntry("DISABLED_REASON", "ADMIN");
        assertThat(statusOf(bobs)).isEqualTo("ACTIVE");
        assertThat(committedEvents.alerts()).containsExactlyInAnyOrder(
                new DomainDisabled(active, "go.example.com", DomainDisableReason.USER_BLOCKED, null),
                new DomainDisabled(waiting, "wait.example.com", DomainDisableReason.USER_BLOCKED, null));
        assertThat(committedEvents.changes()).containsExactlyInAnyOrder(new CustomDomainChangedEvent(active), new CustomDomainChangedEvent(waiting),
                new CustomDomainChangedEvent(pending));
    }

    @Test
    void unblockingTurnsOnOnlyWhatTheBlockTurnedOff() {
        final var blocked = domain(ANA, "go.example.com", "DISABLED", "cf-1");
        final var adminOff = domain(ANA, "off.example.com", "DISABLED", "cf-2");
        final var rule = domain(ANA, "rule.example.com", "DISABLED", "cf-3");
        jdbcTemplate.update("update custom_domain set disabled_reason = 'USER_BLOCKED' where id = ?", blocked);
        jdbcTemplate.update("update custom_domain set disabled_reason = 'ADMIN' where id = ?", adminOff);
        jdbcTemplate.update("update custom_domain set disabled_reason = 'DOMAIN_RULE' where id = ?", rule);

        final var enabled = customDomainModeration.enableForUser(ANA);

        assertThat(enabled).isOne();
        assertThat(statusOf(blocked)).isEqualTo("AWAITING_DNS");
        assertThat(statusOf(adminOff)).isEqualTo("DISABLED");
        assertThat(statusOf(rule)).isEqualTo("DISABLED");
        verify(customDomainLifecycle).checkNow(blocked);
        verify(customDomainLifecycle, never()).checkNow(adminOff);
    }

    private long domain(final long ownerId, final String hostname, final String status, final String cloudflareId) {
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, cloudflare_id, next_check_at, created_at, updated_at)
                values (?, ?, ?, ?, 'Menu links for my cafe', ?, ?, ?, ?)""",
                ownerId, hostname, "REJECTED".equals(status) || "DELETED".equals(status) ? null : hostname, status, cloudflareId, NOW.plusHours(1),
                NOW.minusDays(1), NOW.minusDays(1));
        return jdbcTemplate.queryForObject("select id from custom_domain where hostname = ?", Long.class, hostname);
    }

    private void rule(final String domain, final String type) {
        jdbcTemplate.update("insert into domain_rule (domain, type, reason, created_by) values (?, ?, 'test', 'system')", domain, type);
    }

    private Map<String, Object> row(final long id) {
        return jdbcTemplate.queryForMap("select * from custom_domain where id = ?", id);
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
                    classes = {UserRepository.class, CustomDomainRepository.class, DomainRuleRepository.class}))
    @Import({
            DefaultSettingsTestConfiguration.class,
            AppConfiguration.class,
            CustomDomainModeration.class,
            DefaultLinkCheckService.class,
            TestApplication.FixedClock.class,
            TestApplication.CommittedEvents.class
    })
    static class TestApplication {

        static class CommittedEvents {

            private final List<Object> events = new CopyOnWriteArrayList<>();

            @TransactionalEventListener
            public void onChange(final CustomDomainChangedEvent change) {
                events.add(change);
            }

            @TransactionalEventListener
            public void onAlert(final AdminAlertEvent alert) {
                events.add(alert);
            }

            List<Object> events() {
                return List.copyOf(events);
            }

            List<AdminAlertEvent> alerts() {
                return events.stream().filter(AdminAlertEvent.class::isInstance).map(AdminAlertEvent.class::cast).toList();
            }

            List<CustomDomainChangedEvent> changes() {
                return events.stream().filter(CustomDomainChangedEvent.class::isInstance).map(CustomDomainChangedEvent.class::cast).toList();
            }

            void clear() {
                events.clear();
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
