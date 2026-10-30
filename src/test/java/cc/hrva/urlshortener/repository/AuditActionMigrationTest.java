package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = AuditActionMigrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/audit-action-test-changelog.xml",
                "spring.datasource.url=jdbc:h2:mem:audit-action-migration;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AuditActionMigrationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void everyMethodTheAspectEverAuditedBecomesItsAction() {
        final var rows = rowsByLabel();

        assertThat(Map.ofEntries(
                Map.entry("clearLoginAttempts", AuditAction.CLEAR_LOGIN_ATTEMPTS),
                Map.entry("resendEmail", AuditAction.RESEND_EMAIL),
                Map.entry("updateUser", AuditAction.UPDATE_USER),
                Map.entry("deleteUser", AuditAction.DELETE_USER),
                Map.entry("updateUrl", AuditAction.UPDATE_URL),
                Map.entry("revokeUrl", AuditAction.REVOKE_URL),
                Map.entry("deactivateUrl", AuditAction.REVOKE_URL),
                Map.entry("activateUrl", AuditAction.ACTIVATE_URL),
                Map.entry("deleteUrl", AuditAction.DELETE_URL),
                Map.entry("createSource", AuditAction.CREATE_SOURCE),
                Map.entry("updateSource", AuditAction.UPDATE_SOURCE),
                Map.entry("deleteSource", AuditAction.DELETE_SOURCE),
                Map.entry("refreshSource", AuditAction.REFRESH_SOURCE)))
                .allSatisfy((label, action) -> assertThat(rows.get(label)).satisfies(row -> {
                    assertThat(row.getAction()).isEqualTo(action);
                    assertThat(row.isFailed()).isFalse();
                }));
        assertThat(rows.get("updateUser").getDetails()).isEqualTo("active: false");
        assertThat(rows.get("createSource").getDetails()).isEqualTo("name: HaGeZi Fake");
    }

    @Test
    void aFailedSuffixBecomesTheBaseActionMarkedFailed() {
        final var rows = rowsByLabel();

        assertThat(rows.get("updateUrl_FAILED"))
                .extracting(AuditLog::getAction, AuditLog::isFailed, AuditLog::getDetails)
                .containsExactly(AuditAction.UPDATE_URL, true, "Url with id 9 doesn't exist");
        assertThat(rows.get("createSource_FAILED"))
                .extracting(AuditLog::getAction, AuditLog::isFailed, AuditLog::getDetails)
                .containsExactly(AuditAction.CREATE_SOURCE, true, null);
    }

    @Test
    void theModerationActionsKeepTheirNames() {
        final var rows = rowsByLabel();

        assertThat(rows.get("RESOLVE_REVIEW"))
                .extracting(AuditLog::getAction, AuditLog::isFailed, AuditLog::getDetails)
                .containsExactly(AuditAction.RESOLVE_REVIEW, false, "action: OK, links: 0, via: admin");
        assertThat(rows.get("BLOCK_DOMAIN"))
                .extracting(AuditLog::getAction, AuditLog::isFailed, AuditLog::getDetails)
                .containsExactly(AuditAction.BLOCK_DOMAIN, false, "reason: phishing wave, links: 3, via: mcp");
    }

    @Test
    void aLegacyValueThatMatchesNoActionLoadsAsUnknownAndKeepsTheOriginalInEmptyDetails() {
        final var rows = rowsByLabel();

        assertThat(rows.get("ancientThing"))
                .extracting(AuditLog::getAction, AuditLog::isFailed, AuditLog::getDetails)
                .containsExactly(AuditAction.UNKNOWN, false, "ancientThing");
        assertThat(rows.get("ancientThing_FAILED"))
                .extracting(AuditLog::getAction, AuditLog::isFailed, AuditLog::getDetails)
                .containsExactly(AuditAction.UNKNOWN, true, "ancientThing_FAILED");
        assertThat(rows.get("mysteryAction"))
                .extracting(AuditLog::getAction, AuditLog::isFailed, AuditLog::getDetails)
                .containsExactly(AuditAction.UNKNOWN, false, "kept as it was");
    }

    @Test
    void theLogFiltersByActionNewestFirst() {
        final var revoked = auditLogRepository.findAllByAction(AuditAction.REVOKE_URL, PageRequest.of(0, 20, Sort.by(Sort.Order.desc("performedAt"))));

        assertThat(revoked.getContent()).extracting(AuditLog::getTargetIdentifier).containsExactly("deactivateUrl", "revokeUrl");
        assertThat(auditLogRepository.findAllByAction(AuditAction.DELETE_LINK, PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void aNewEntryStoresTheActionNameAndIsNotFailedByDefault() {
        final var saved = auditLogRepository.saveAndFlush(AuditLog.builder()
                .performedBy("admin@hrva.cc")
                .action(AuditAction.BLOCK_DOMAIN)
                .targetType("Domain")
                .targetIdentifier("jofogas.cards")
                .build());
        entityManager.clear();

        assertThat(entityManager.createNativeQuery("SELECT action, failed FROM audit_log WHERE id = " + saved.getId()).getSingleResult())
                .isEqualTo(new Object[] {"BLOCK_DOMAIN", false});
    }

    private Map<String, AuditLog> rowsByLabel() {
        return auditLogRepository.findAll().stream().collect(Collectors.toMap(AuditLog::getTargetIdentifier, Function.identity()));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AuditLog.class)
    @EnableJpaRepositories(
            basePackageClasses = AuditLogRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AuditLogRepository.class))
    static class TestApplication {
    }

}
