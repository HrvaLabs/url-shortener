package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.CustomDomainAllowance;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.MissingDomain;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.ReconcileRun;
import cc.hrva.urlshortener.repository.UrlDomainMoves.DomainLink;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = CustomDomainSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:domain-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class CustomDomainSchemaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);
    private static final String DOMAIN_ROW = "select status, check_count, last_checked_at, activated_at, problem, last_error, next_check_at, "
            + "hostname_key, cloudflare_delete_pending, deleted_at, disabled_reason, disabled_note from custom_domain where id = ?";

    @Autowired
    private CustomDomainRepository customDomainRepository;

    @Autowired
    private CustomDomainAllowanceRepository customDomainAllowanceRepository;

    @Autowired
    private CustomDomainReconcileRepository customDomainReconcileRepository;

    @Autowired
    private CustomDomainCapLock customDomainCapLock;

    @Autowired
    private UrlDomainMoves urlDomainMoves;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShortUrlUniqueIndex shortUrlUniqueIndex;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void everyChangesetOf032Ran() {
        assertThat(jdbcTemplate.queryForList("select id, exectype from databasechangelog where id like '032-%' order by orderexecuted"))
                .extracting(row -> row.get("ID"), row -> row.get("EXECTYPE"))
                .containsExactly(
                        tuple("032-custom-domain", "EXECUTED"),
                        tuple("032-custom-domain-allowance", "EXECUTED"),
                        tuple("032-custom-domain-reconcile", "EXECUTED"),
                        tuple("032-custom-domain-cap-lock", "EXECUTED"),
                        tuple("032-url-domain-id", "EXECUTED"),
                        tuple("032-url-domain-short-url-unique", "EXECUTED"),
                        tuple("032-url-short-url-unique-dropped", "EXECUTED"));
    }

    @Test
    void keysAndIndexesCarryTheirNames() {
        assertThat(jdbcTemplate.queryForList("select upper(constraint_name) from information_schema.table_constraints", String.class))
                .contains("FK_CUSTOM_DOMAIN_OWNER", "PK_CUSTOM_DOMAIN_ALLOWANCE", "FK_CUSTOM_DOMAIN_ALLOWANCE_USER", "FK_URL_CUSTOM_DOMAIN");
        assertThat(jdbcTemplate.queryForList("select upper(index_name) from information_schema.indexes", String.class))
                .contains(
                        "UX_CUSTOM_DOMAIN_HOSTNAME_KEY",
                        "IDX_CUSTOM_DOMAIN_OWNER_ID",
                        "IDX_CUSTOM_DOMAIN_DUE",
                        "IDX_CUSTOM_DOMAIN_DELETE_PENDING",
                        "IDX_CUSTOM_DOMAIN_RECONCILE_RAN_AT",
                        "IDX_URL_DOMAIN_ID");
        assertThat(jdbcTemplate.queryForList("select id from custom_domain_cap_lock", Integer.class)).containsExactly(1);
    }

    @Test
    void twoHrvaLinksCannotShareAnAlias() {
        urlRepository.saveAndFlush(Url.builder().shortUrl("menu").longUrl("https://example.com/first").build());

        assertThatThrownBy(() -> urlRepository.saveAndFlush(Url.builder().shortUrl("menu").longUrl("https://example.com/second").build()))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        exception -> assertThat(ShortUrlUniqueIndex.violatedBy(exception)).isTrue());
    }

    @Test
    void sameAliasOnTwoDomainsCoexist() {
        final var first = savedDomain(domain("go.example.com", CustomDomainStatus.ACTIVE));
        final var second = savedDomain(domain("go.example.org", CustomDomainStatus.ACTIVE));

        insertLink("menu", null);
        insertLink("menu", first.getId());
        insertLink("menu", second.getId());

        assertThat(jdbcTemplate.queryForObject("select count(*) from url where short_url = 'menu'", Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> insertLink("menu", first.getId()))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        exception -> assertThat(exception.getMessage()).containsIgnoringCase("UX_URL_DOMAIN_SHORT_URL"));
    }

    @Test
    void theOldShortUrlIndexIsGone() {
        assertThat(jdbcTemplate.queryForList("select upper(index_name) from information_schema.indexes", String.class))
                .doesNotContain("UX_URL_SHORT_URL");
        assertThat(jdbcTemplate.queryForList("""
                select index_type_name, nulls_distinct from information_schema.indexes
                where upper(index_name) = 'UX_URL_DOMAIN_SHORT_URL'"""))
                .extracting(row -> row.get("INDEX_TYPE_NAME"), row -> row.get("NULLS_DISTINCT"))
                .containsExactly(tuple("UNIQUE INDEX", "NO"));
        assertThat(jdbcTemplate.queryForList("""
                select c.column_name from information_schema.index_columns c
                where upper(c.index_name) = 'UX_URL_DOMAIN_SHORT_URL' order by c.ordinal_position""", String.class))
                .containsExactly("DOMAIN_ID", "SHORT_URL");
    }

    @Test
    void uniquenessIsEnforcedByTheCompositeIndex() {
        assertThat(shortUrlUniqueIndex.enforced()).isTrue();
    }

    @Test
    void aSkippedCompositeIndexIsNotEnforcedEvenWhere027MadeTheOldOne() {
        jdbcTemplate.update("update databasechangelog set exectype = 'MARK_RAN' where id = '032-url-domain-short-url-unique'");

        assertThat(shortUrlUniqueIndex.enforced()).isFalse();
    }

    @Test
    void hostnameKeyIsUniqueButNullsRepeat() {
        savedDomain(domain("go.example.com", CustomDomainStatus.PENDING_REVIEW));
        savedDomain(domain("go.example.com", CustomDomainStatus.REJECTED).hostnameKey(null));
        savedDomain(domain("go.example.com", CustomDomainStatus.DELETED).hostnameKey(null));

        assertThatThrownBy(() -> savedDomain(domain("go.example.com", CustomDomainStatus.PENDING_REVIEW)))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        exception -> assertThat(exception.getMessage()).containsIgnoringCase("UX_CUSTOM_DOMAIN_HOSTNAME_KEY"));
    }

    @Test
    void deletingTheOwnerNullsTheDomainOwner() {
        final var owner = savedUser("ana@example.com");
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.ACTIVE).owner(owner));
        customDomainAllowanceRepository.saveAndFlush(allowance(owner.getId()));
        insertLink("menu", domain.getId());
        entityManager.clear();

        jdbcTemplate.update("delete from url where domain_id = ?", domain.getId());
        jdbcTemplate.update("delete from user_account where id = ?", owner.getId());

        assertThat(jdbcTemplate.queryForList("select owner_id, hostname from custom_domain where id = ?", domain.getId()))
                .extracting(row -> row.get("OWNER_ID"), row -> row.get("HOSTNAME"))
                .containsExactly(tuple(null, "go.example.com"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from custom_domain_allowance", Integer.class)).isZero();
    }

    @Test
    void anAllowanceIsKeyedByItsUser() {
        final var owner = savedUser("ana@example.com");
        customDomainAllowanceRepository.saveAndFlush(allowance(owner.getId()));
        entityManager.clear();

        assertThat(customDomainAllowanceRepository.findById(owner.getId())).hasValueSatisfying(saved -> {
            assertThat(saved.getMaxDomains()).isEqualTo(3);
            assertThat(saved.getReason()).isEqualTo("Agency with three brands");
            assertThat(saved.getGrantedBy()).isEqualTo("admin@localhost");
            assertThat(saved.getGrantedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void aDomainWithLinksCannotBeDeleted() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.ACTIVE));
        insertLink("menu", domain.getId());

        assertThatThrownBy(() -> jdbcTemplate.update("delete from custom_domain where id = ?", domain.getId()))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        exception -> assertThat(exception.getMessage()).containsIgnoringCase("FK_URL_CUSTOM_DOMAIN"));
    }

    @Test
    void slotsCountEveryHostnameInCloudflare() {
        savedDomain(domain("pending.example.com", CustomDomainStatus.PENDING_REVIEW));
        savedDomain(domain("approved.example.com", CustomDomainStatus.APPROVED_PROVISIONING));
        savedDomain(domain("awaiting.example.com", CustomDomainStatus.AWAITING_DNS).cloudflareId("cf-1"));
        savedDomain(domain("active.example.com", CustomDomainStatus.ACTIVE).cloudflareId("cf-2"));
        savedDomain(domain("disabled.example.com", CustomDomainStatus.DISABLED).cloudflareId("cf-3"));
        savedDomain(domain("error.example.com", CustomDomainStatus.ERROR).cloudflareId("cf-4"));
        savedDomain(domain("never-created.example.com", CustomDomainStatus.ERROR).problem(DomainProblem.PROVISIONING_FAILED));
        savedDomain(domain("rejected.example.com", CustomDomainStatus.REJECTED).hostnameKey(null));
        savedDomain(domain("leaving.example.com", CustomDomainStatus.DELETED).cloudflareId("cf-5").cloudflareDeletePending(true));
        savedDomain(domain("left.example.com", CustomDomainStatus.DELETED).cloudflareId("cf-6").hostnameKey(null));
        savedDomain(domain("withdrawn.example.com", CustomDomainStatus.DELETED).hostnameKey(null));

        assertThat(customDomainRepository.countSlotsInUse()).isEqualTo(6);
    }

    @Test
    void findDueIdsOrdersNullsFirst() {
        final var later = savedDomain(domain("later.example.com", CustomDomainStatus.AWAITING_DNS).nextCheckAt(NOW.minusMinutes(10)));
        final var sameTime = savedDomain(domain("same.example.com", CustomDomainStatus.AWAITING_DNS).nextCheckAt(NOW.minusMinutes(10)));
        final var earlier = savedDomain(domain("earlier.example.com", CustomDomainStatus.AWAITING_DNS).nextCheckAt(NOW.minusMinutes(20)));
        final var never = savedDomain(domain("never.example.com", CustomDomainStatus.AWAITING_DNS));
        final var exactlyNow = savedDomain(domain("now.example.com", CustomDomainStatus.AWAITING_DNS).nextCheckAt(NOW));
        savedDomain(domain("future.example.com", CustomDomainStatus.AWAITING_DNS).nextCheckAt(NOW.plusMinutes(1)));
        savedDomain(domain("active.example.com", CustomDomainStatus.ACTIVE));

        assertThat(customDomainRepository.findDueIds(CustomDomainStatus.AWAITING_DNS, NOW, Limit.of(50)))
                .containsExactly(never.getId(), earlier.getId(), later.getId(), sameTime.getId(), exactlyNow.getId());
        assertThat(customDomainRepository.findDueIds(CustomDomainStatus.AWAITING_DNS, NOW, Limit.of(2)))
                .containsExactly(never.getId(), earlier.getId());
    }

    @Test
    void aStoppedErrorRowIsNeverDue() {
        savedDomain(domain("stopped.example.com", CustomDomainStatus.ERROR).problem(DomainProblem.DNS_TIMED_OUT));
        savedDomain(domain("failed.example.com", CustomDomainStatus.ERROR).problem(DomainProblem.PROVISIONING_FAILED));
        final var rechecked = savedDomain(domain("recheck.example.com", CustomDomainStatus.ERROR).problem(DomainProblem.CNAME_REMOVED)
                .nextCheckAt(NOW.minusMinutes(5)));

        assertThat(customDomainRepository.findDueIds(CustomDomainStatus.ERROR, NOW, Limit.of(50))).containsExactly(rechecked.getId());
    }

    @Test
    void aGuardedUpdateIsSeenByTheNextReadInTheSameTransaction() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.PENDING_REVIEW));
        assertThat(customDomainRepository.findById(domain.getId())).map(CustomDomain::getStatus).contains(CustomDomainStatus.PENDING_REVIEW);

        assertThat(customDomainRepository.markApproved(domain.getId(), "admin@localhost", null, NOW)).isOne();

        assertThat(customDomainRepository.findById(domain.getId())).hasValueSatisfying(reread -> {
            assertThat(reread.getStatus()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
            assertThat(reread.getReviewedBy()).isEqualTo("admin@localhost");
            assertThat(reread.getNextCheckAt()).isEqualTo(NOW);
        });
    }

    @Test
    void findDeletePendingIdsListsOnlyQueuedDeletes() {
        final var second = savedDomain(domain("b.example.com", CustomDomainStatus.DELETED).cloudflareId("cf-2").cloudflareDeletePending(true));
        savedDomain(domain("c.example.com", CustomDomainStatus.DELETED).cloudflareId("cf-3").hostnameKey(null));
        final var third = savedDomain(domain("d.example.com", CustomDomainStatus.DELETED).cloudflareId("cf-4").cloudflareDeletePending(true));

        assertThat(customDomainRepository.findDeletePendingIds(Limit.of(50))).containsExactly(second.getId(), third.getId());
        assertThat(customDomainRepository.findDeletePendingIds(Limit.of(1))).containsExactly(second.getId());
    }

    @Test
    void guardedUpdatesTouchOnlyTheExpectedStatus() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.AWAITING_DNS).cloudflareId("cf-1"));

        assertThat(customDomainRepository.recordCheck(domain.getId(), CustomDomainStatus.ACTIVE, CustomDomainStatus.ERROR, "moved", "active",
                DomainProblem.CNAME_REMOVED, "gone", null, NOW.plusHours(1), NOW)).isZero();
        assertThat(domainRow(domain.getId()))
                .containsEntry("STATUS", "AWAITING_DNS")
                .containsEntry("CHECK_COUNT", 0)
                .containsEntry("LAST_CHECKED_AT", null)
                .containsEntry("PROBLEM", null);

        assertThat(customDomainRepository.recordCheck(domain.getId(), CustomDomainStatus.AWAITING_DNS, CustomDomainStatus.ACTIVE, "active", "active",
                null, null, NOW, NOW.plusHours(1), NOW)).isOne();
        assertThat(customDomainRepository.recordCheck(domain.getId(), CustomDomainStatus.ACTIVE, CustomDomainStatus.ACTIVE, "active", "active",
                null, null, NOW.plusMinutes(5), NOW.plusHours(2), NOW.plusMinutes(5))).isOne();
        assertThat(domainRow(domain.getId()))
                .containsEntry("STATUS", "ACTIVE")
                .containsEntry("CHECK_COUNT", 2)
                .containsEntry("LAST_CHECKED_AT", timestamp(NOW.plusMinutes(5)))
                .containsEntry("ACTIVATED_AT", timestamp(NOW))
                .containsEntry("NEXT_CHECK_AT", timestamp(NOW.plusHours(2)));
    }

    @Test
    void reviewIsGuardedByPendingReview() {
        final var approved = savedDomain(domain("go.example.com", CustomDomainStatus.PENDING_REVIEW));
        final var rejected = savedDomain(domain("go.example.org", CustomDomainStatus.PENDING_REVIEW));

        assertThat(customDomainRepository.markApproved(approved.getId(), "admin@localhost", null, NOW)).isOne();
        assertThat(customDomainRepository.markRejected(approved.getId(), "admin@localhost", "No", NOW)).isZero();
        assertThat(customDomainRepository.markRejected(rejected.getId(), "admin@localhost", "Not yours", NOW)).isOne();
        assertThat(customDomainRepository.markApproved(rejected.getId(), "admin@localhost", null, NOW)).isZero();

        assertThat(domainRow(approved.getId()))
                .containsEntry("STATUS", "APPROVED_PROVISIONING")
                .containsEntry("NEXT_CHECK_AT", timestamp(NOW))
                .containsEntry("HOSTNAME_KEY", "go.example.com");
        assertThat(domainRow(rejected.getId()))
                .containsEntry("STATUS", "REJECTED")
                .containsEntry("HOSTNAME_KEY", null);
    }

    @Test
    void provisioningRetriesUntilThereIsNoNextTryThenFails() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.APPROVED_PROVISIONING).nextCheckAt(NOW));

        assertThat(customDomainRepository.recordProvisioningFailure(domain.getId(), "Cloudflare said no", NOW.plusMinutes(1), NOW)).isOne();
        assertThat(domainRow(domain.getId()))
                .containsEntry("STATUS", "APPROVED_PROVISIONING")
                .containsEntry("PROBLEM", null)
                .containsEntry("LAST_ERROR", "Cloudflare said no")
                .containsEntry("NEXT_CHECK_AT", timestamp(NOW.plusMinutes(1)))
                .containsEntry("CHECK_COUNT", 1);

        assertThat(customDomainRepository.recordProvisioningFailure(domain.getId(), "Still no", null, NOW.plusDays(1))).isOne();
        assertThat(domainRow(domain.getId()))
                .containsEntry("STATUS", "ERROR")
                .containsEntry("PROBLEM", "PROVISIONING_FAILED")
                .containsEntry("LAST_ERROR", "Still no")
                .containsEntry("NEXT_CHECK_AT", null);
        assertThat(customDomainRepository.recordProvisioningFailure(domain.getId(), "Again", NOW, NOW)).isZero();
    }

    @Test
    void creatingMovesProvisioningToAwaitingDns() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.APPROVED_PROVISIONING).lastError("earlier failure"));

        assertThat(customDomainRepository.recordCreated(domain.getId(), "cf-1", NOW, NOW.plusMinutes(1))).isOne();
        assertThat(customDomainRepository.recordCreated(domain.getId(), "cf-2", NOW, NOW.plusMinutes(1))).isZero();
        assertThat(domainRow(domain.getId()))
                .containsEntry("STATUS", "AWAITING_DNS")
                .containsEntry("LAST_ERROR", null)
                .containsEntry("NEXT_CHECK_AT", timestamp(NOW.plusMinutes(1)));
        assertThat(jdbcTemplate.queryForObject("select cloudflare_id from custom_domain where id = ?", String.class, domain.getId())).isEqualTo("cf-1");
    }

    @Test
    void disableAndEnableKeepTheirStatusGuards() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.ACTIVE).cloudflareId("cf-1").nextCheckAt(NOW));
        final var pending = savedDomain(domain("go.example.org", CustomDomainStatus.PENDING_REVIEW));

        assertThat(customDomainRepository.markDisabled(pending.getId(), CustomDomainStatus.PENDING_REVIEW, DomainDisableReason.ADMIN, "No", NOW)).isZero();
        assertThat(customDomainRepository.markDisabled(domain.getId(), CustomDomainStatus.AWAITING_DNS, DomainDisableReason.ADMIN, "No", NOW)).isZero();
        assertThat(customDomainRepository.markEnabled(domain.getId(), NOW)).isZero();
        assertThat(customDomainRepository.markDisabled(domain.getId(), CustomDomainStatus.ACTIVE, DomainDisableReason.ADMIN, "Phishing", NOW)).isOne();
        assertThat(domainRow(domain.getId()))
                .containsEntry("STATUS", "DISABLED")
                .containsEntry("DISABLED_REASON", "ADMIN")
                .containsEntry("DISABLED_NOTE", "Phishing")
                .containsEntry("NEXT_CHECK_AT", null);

        assertThat(customDomainRepository.markEnabled(domain.getId(), NOW.plusHours(1))).isOne();
        assertThat(domainRow(domain.getId()))
                .containsEntry("STATUS", "AWAITING_DNS")
                .containsEntry("DISABLED_REASON", null)
                .containsEntry("DISABLED_NOTE", null)
                .containsEntry("NEXT_CHECK_AT", timestamp(NOW.plusHours(1)));
    }

    @Test
    void deletingKeepsTheHostnameOnlyWhileCloudflareStillHasIt() {
        final var inCloudflare = savedDomain(domain("go.example.com", CustomDomainStatus.ACTIVE).cloudflareId("cf-1"));
        final var neverCreated = savedDomain(domain("go.example.org", CustomDomainStatus.ERROR).problem(DomainProblem.PROVISIONING_FAILED));

        assertThat(customDomainRepository.markDeleted(inCloudflare.getId(), CustomDomainStatus.AWAITING_DNS, NOW)).isZero();
        assertThat(customDomainRepository.markDeleted(inCloudflare.getId(), CustomDomainStatus.ACTIVE, NOW)).isOne();
        assertThat(customDomainRepository.markDeleted(neverCreated.getId(), CustomDomainStatus.ERROR, NOW)).isOne();
        assertThat(customDomainRepository.markDeleted(neverCreated.getId(), CustomDomainStatus.DELETED, NOW)).isZero();

        assertThat(domainRow(inCloudflare.getId()))
                .containsEntry("STATUS", "DELETED")
                .containsEntry("DELETED_AT", timestamp(NOW))
                .containsEntry("CLOUDFLARE_DELETE_PENDING", true)
                .containsEntry("HOSTNAME_KEY", "go.example.com");
        assertThat(domainRow(neverCreated.getId()))
                .containsEntry("STATUS", "DELETED")
                .containsEntry("CLOUDFLARE_DELETE_PENDING", false)
                .containsEntry("HOSTNAME_KEY", null);

        assertThat(customDomainRepository.clearDeletePending(inCloudflare.getId(), NOW)).isOne();
        assertThat(customDomainRepository.clearDeletePending(inCloudflare.getId(), NOW)).isZero();
        assertThat(domainRow(inCloudflare.getId()))
                .containsEntry("CLOUDFLARE_DELETE_PENDING", false)
                .containsEntry("HOSTNAME_KEY", null);
    }

    @Test
    void onlyAnOwnerlessRowWithNothingQueuedIsDeletedAsAnOrphan() {
        final var owned = savedDomain(domain("owned.example.com", CustomDomainStatus.DELETED).owner(savedUser("ana@example.com")).hostnameKey(null));
        final var queued = savedDomain(domain("queued.example.com", CustomDomainStatus.DELETED).cloudflareId("cf-1").cloudflareDeletePending(true));
        final var orphan = savedDomain(domain("orphan.example.com", CustomDomainStatus.DELETED).hostnameKey(null));

        assertThat(customDomainRepository.deleteOrphan(owned.getId())).isZero();
        assertThat(customDomainRepository.deleteOrphan(queued.getId())).isZero();
        assertThat(customDomainRepository.deleteOrphan(orphan.getId())).isOne();
        assertThat(jdbcTemplate.queryForList("select id from custom_domain order by id", Long.class)).containsExactly(owned.getId(), queued.getId());
    }

    @Test
    void ownedRowsAreFoundByTheirOwner() {
        final var ana = savedUser("ana@example.com");
        final var bob = savedUser("bob@example.com");
        final var older = savedDomain(domain("old.example.com", CustomDomainStatus.REJECTED).owner(ana).hostnameKey(null).createdAt(NOW.minusDays(1)));
        final var newer = savedDomain(domain("new.example.com", CustomDomainStatus.ACTIVE).owner(ana));
        final var bobs = savedDomain(domain("bob.example.com", CustomDomainStatus.PENDING_REVIEW).owner(bob));
        entityManager.clear();

        assertThat(customDomainRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(ana.getId())).extracting(CustomDomain::getId)
                .containsExactly(newer.getId(), older.getId());
        assertThat(customDomainRepository.findByIdAndOwnerId(bobs.getId(), ana.getId())).isEmpty();
        assertThat(customDomainRepository.findByIdAndOwnerId(newer.getId(), ana.getId())).isPresent();
        assertThat(customDomainRepository.countByOwnerIdAndStatusIn(ana.getId(), CustomDomainStatus.COUNTS_FOR_USER)).isOne();
        assertThat(customDomainRepository.findByHostnameKey("bob.example.com")).map(CustomDomain::getId).contains(bobs.getId());
        assertThat(customDomainRepository.findByStatusIn(List.of(CustomDomainStatus.ACTIVE, CustomDomainStatus.PENDING_REVIEW)))
                .extracting(CustomDomain::getId).containsExactlyInAnyOrder(newer.getId(), bobs.getId());
        assertThat(customDomainRepository.findWithOwner(newer.getId())).hasValueSatisfying(found ->
                assertThat(found.getOwner().getEmail()).isEqualTo("ana@example.com"));
    }

    @Test
    void applyFlagsAreStoredAsLines() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.PENDING_REVIEW)
                .applyFlags(List.of("Registered 3 days ago", "Looks like a bank")));
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject("select apply_flags from custom_domain where id = ?", String.class, domain.getId()))
                .isEqualTo("Registered 3 days ago\nLooks like a bank");
        assertThat(customDomainRepository.findById(domain.getId())).hasValueSatisfying(found ->
                assertThat(found.getApplyFlags()).containsExactly("Registered 3 days ago", "Looks like a bank"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void capLockSerializesTwoTransactions() throws Exception {
        final var transaction = new TransactionTemplate(transactionManager);
        final var barrier = new CyclicBarrier(2);

        final var executor = Executors.newFixedThreadPool(2);
        try {
            final var first = CompletableFuture.supplyAsync(() -> lockedFor200Milliseconds(transaction, barrier), executor);
            final var second = CompletableFuture.supplyAsync(() -> lockedFor200Milliseconds(transaction, barrier), executor);

            final var sections = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)).stream()
                    .sorted(Comparator.comparingLong(CriticalSection::enteredAt))
                    .toList();
            assertThat(sections.get(1).enteredAt()).isGreaterThanOrEqualTo(sections.get(0).leftAt());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theCapLockNeedsAWriteTransaction() {
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> customDomainCapLock.lock()).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> customDomainCapLock.lock()))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessage("Locking the custom domain cap needs a read-write transaction");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void urlReadsItsDomainEagerly() {
        final var transaction = new TransactionTemplate(transactionManager);
        final var domain = transaction.execute(status -> savedDomain(domain("go.example.com", CustomDomainStatus.ACTIVE)));
        final var link = transaction.execute(status -> urlRepository.saveAndFlush(
                Url.builder().shortUrl("menu").longUrl("https://example.com/menu").domain(domain).build()));
        try {
            final var byId = transaction.execute(status -> urlRepository.findById(link.getId()).orElseThrow());
            final var served = transaction.execute(status -> urlRepository.findServed(domain.getId(), "menu").orElseThrow());

            assertThat(byId.getDomain().getHostname()).isEqualTo("go.example.com");
            assertThat(served.getDomain().getHostname()).isEqualTo("go.example.com");
            assertThat(served.domainId()).isEqualTo(domain.getId());
        } finally {
            jdbcTemplate.update("delete from url where id = ?", link.getId());
            jdbcTemplate.update("delete from custom_domain where id = ?", domain.getId());
        }
    }

    @Test
    void aliasLookupsAreScopedToTheirDomain() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.ACTIVE));
        final var other = savedDomain(domain("go.example.org", CustomDomainStatus.ACTIVE));
        final var hrva = urlRepository.saveAndFlush(Url.builder().shortUrl("menu").longUrl("https://example.com/hrva").build());
        final var onDomain = urlRepository.saveAndFlush(Url.builder().shortUrl("menu").longUrl("https://example.com/domain").domain(domain).build());
        final var switchedOff = urlRepository.saveAndFlush(Url.builder().shortUrl("old").longUrl("https://example.com/old").domain(domain).build());
        jdbcTemplate.update("update url set active = false where id = ?", switchedOff.getId());
        entityManager.clear();

        assertThat(urlRepository.findServed(null, "menu")).map(Url::getId).contains(hrva.getId());
        assertThat(urlRepository.findServed(domain.getId(), "menu")).map(Url::getId).contains(onDomain.getId());
        assertThat(urlRepository.findServed(other.getId(), "menu")).isEmpty();
        assertThat(urlRepository.findServed(domain.getId(), "old")).map(Url::getId).contains(switchedOff.getId());
        assertThat(urlRepository.findServed(null, "old")).isEmpty();
        assertThat(urlRepository.findActive(domain.getId(), "menu")).map(Url::getId).contains(onDomain.getId());
        assertThat(urlRepository.findActive(domain.getId(), "old")).isEmpty();
        assertThat(urlRepository.findActive(null, "menu")).map(Url::getId).contains(hrva.getId());
        assertThat(urlRepository.aliasTaken(null, "old")).isFalse();
        assertThat(urlRepository.aliasTaken(domain.getId(), "old")).isTrue();
        assertThat(urlRepository.aliasTaken(other.getId(), "menu")).isFalse();
        assertThat(urlRepository.aliasActive(domain.getId(), "old")).isFalse();
        assertThat(urlRepository.aliasActive(domain.getId(), "menu")).isTrue();
        assertThat(urlRepository.aliasActive(null, "menu")).isTrue();
    }

    @Test
    void moveToPrimaryIsGuardedByTheDomain() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.ACTIVE));
        final var other = savedDomain(domain("go.example.org", CustomDomainStatus.ACTIVE));
        final var menu = insertLink("menu", domain.getId());
        final var card = insertLink("card", domain.getId());

        assertThat(urlDomainMoves.linksOf(domain.getId())).containsExactly(new DomainLink(menu, "menu", true), new DomainLink(card, "card", true));
        assertThat(urlDomainMoves.moveToPrimary(menu, other.getId(), "x")).isFalse();
        assertThat(jdbcTemplate.queryForMap("select domain_id, short_url from url where id = ?", menu))
                .containsEntry("DOMAIN_ID", domain.getId())
                .containsEntry("SHORT_URL", "menu");

        assertThat(urlDomainMoves.moveToPrimary(menu, domain.getId(), "k3J9x")).isTrue();
        assertThat(jdbcTemplate.queryForMap("select domain_id, short_url from url where id = ?", menu))
                .containsEntry("DOMAIN_ID", null)
                .containsEntry("SHORT_URL", "k3J9x");
        assertThat(urlDomainMoves.linksOf(domain.getId())).containsExactly(new DomainLink(card, "card", true));
    }

    @Test
    void moveToPrimaryFailsOnATakenAlias() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.ACTIVE));
        insertLink("menu", null);
        final var menu = insertLink("menu", domain.getId());

        assertThatThrownBy(() -> urlDomainMoves.moveToPrimary(menu, domain.getId(), "menu")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deactivateAllTurnsOffOnlyThatDomainsActiveLinks() {
        final var domain = savedDomain(domain("go.example.com", CustomDomainStatus.ACTIVE));
        final var other = savedDomain(domain("go.example.org", CustomDomainStatus.ACTIVE));
        final var menu = insertLink("menu", domain.getId());
        final var off = insertLink("off", domain.getId());
        jdbcTemplate.update("update url set active = false where id = ?", off);
        final var elsewhere = insertLink("menu", other.getId());
        final var hrva = insertLink("menu", null);

        assertThat(urlDomainMoves.deactivateAll(domain.getId())).isOne();
        assertThat(urlDomainMoves.linksOf(domain.getId())).containsExactly(new DomainLink(menu, "menu", false), new DomainLink(off, "off", false));
        assertThat(jdbcTemplate.queryForList("select id from url where active = true order by id", Long.class)).containsExactly(elsewhere, hrva);
    }

    @Test
    void reconcileRunRoundTrips() {
        final var empty = new ReconcileRun(NOW.minusDays(40), null, null, List.of(), List.of(), "Cloudflare couldn't be reached");
        final var one = new ReconcileRun(NOW.minusDays(1), 4, 3, List.of("test.karlo.cc"), List.of(new MissingDomain(9, "x.example.org")), null);
        final var three = new ReconcileRun(NOW, 7, 4, List.of("a.example.com", "b.example.com", "c.example.com"),
                List.of(new MissingDomain(9, "x.example.org"), new MissingDomain(10, "y.example.org"), new MissingDomain(11, "z.example.org")), null);

        assertThat(customDomainReconcileRepository.newest()).isEmpty();
        customDomainReconcileRepository.insert(empty);
        assertThat(customDomainReconcileRepository.newest()).contains(empty);
        customDomainReconcileRepository.insert(three);
        customDomainReconcileRepository.insert(one);
        assertThat(customDomainReconcileRepository.newest()).contains(three);
        assertThat(jdbcTemplate.queryForList("select unknown_hostnames, missing_domain_ids from custom_domain_reconcile order by ran_at"))
                .extracting(row -> row.get("UNKNOWN_HOSTNAMES"), row -> row.get("MISSING_DOMAIN_IDS"))
                .containsExactly(
                        tuple(null, null),
                        tuple("test.karlo.cc", "9:x.example.org"),
                        tuple("a.example.com\nb.example.com\nc.example.com", "9:x.example.org\n10:y.example.org\n11:z.example.org"));

        assertThat(customDomainReconcileRepository.deleteRanBefore(NOW.minusDays(30))).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from custom_domain_reconcile", Integer.class)).isEqualTo(2);
        assertThat(customDomainReconcileRepository.newest()).contains(three);
    }

    private record CriticalSection(long enteredAt, long leftAt) {
    }

    private CriticalSection lockedFor200Milliseconds(final TransactionTemplate transaction, final CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return transaction.execute(status -> {
                customDomainCapLock.lock();
                final var entered = System.nanoTime();
                try {
                    Thread.sleep(200);
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return new CriticalSection(entered, System.nanoTime());
            });
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> domainRow(final long id) {
        return jdbcTemplate.queryForMap(DOMAIN_ROW, id);
    }

    private static Timestamp timestamp(final LocalDateTime value) {
        return Timestamp.valueOf(value);
    }

    private long insertLink(final String shortUrl, final Long domainId) {
        jdbcTemplate.update("insert into url (short_url, long_url, domain_id, active) values (?, ?, ?, true)",
                shortUrl, "https://example.com/" + shortUrl, domainId);
        return jdbcTemplate.queryForObject("select max(id) from url", Long.class);
    }

    private User savedUser(final String email) {
        return userRepository.saveAndFlush(User.builder().email(email).password("correct horse battery").apiKeySlots(4L).build());
    }

    private CustomDomain savedDomain(final CustomDomain.CustomDomainBuilder builder) {
        return customDomainRepository.saveAndFlush(builder.build());
    }

    private static CustomDomain.CustomDomainBuilder domain(final String hostname, final CustomDomainStatus status) {
        return CustomDomain.builder()
                .hostname(hostname)
                .hostnameKey(hostname)
                .status(status)
                .reason("Menu links for my cafe")
                .createdAt(NOW)
                .updatedAt(NOW);
    }

    private static CustomDomainAllowance allowance(final long userId) {
        return CustomDomainAllowance.builder()
                .userId(userId)
                .maxDomains(3)
                .reason("Agency with three brands")
                .grantedBy("admin@localhost")
                .grantedAt(NOW)
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @Import({CustomDomainReconcileRepository.class, CustomDomainCapLock.class, UrlDomainMoves.class, ShortUrlUniqueIndex.class})
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {
                            CustomDomainRepository.class,
                            CustomDomainAllowanceRepository.class,
                            UrlRepository.class,
                            UserRepository.class}))
    static class TestApplication {
    }

}
