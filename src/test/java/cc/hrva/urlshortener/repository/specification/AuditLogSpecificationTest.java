package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.dto.AuditLogFilter;
import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import java.time.LocalDate;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = AuditLogSpecificationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:audit-log-specification;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AuditLogSpecificationTest {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("performedAt"), Sort.Order.desc("id"));

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        persistEntry("karlo@hrva.cc", AuditAction.BLOCK_DOMAIN, "DomainRule", "findfavoritedate.life", "links: 4, via: admin",
                LocalDateTime.of(2026, 10, 1, 0, 0));
        persistEntry("Admin@Hrva.cc", AuditAction.REVOKE_URL, "Url", "287", "reason: Phishing lure", LocalDateTime.of(2026, 10, 14, 12, 30));
        persistEntry("karlo@hrva.cc", AuditAction.RESOLVE_REVIEW, "Url", "288", "action: OK, via: mcp", LocalDateTime.of(2026, 10, 23, 23, 59));
        persistEntry("karlo@hrva.cc", AuditAction.REVOKE_URL, "Url", "50%_off", null, LocalDateTime.of(2026, 10, 24, 0, 0));
        entityManager.flush();
        entityManager.clear();
    }

    private void persistEntry(final String performedBy, final AuditAction action, final String targetType, final String target,
                              final String details, final LocalDateTime performedAt) {
        final var entry = AuditLog.builder().performedBy(performedBy).action(action).targetType(targetType).targetIdentifier(target)
                .details(details).build();
        entityManager.persist(entry);
        entityManager.flush();
        entry.setPerformedAt(performedAt);
    }

    private List<String> targets(final AuditLogFilter filter) {
        return new DefaultAuditLogService(auditLogRepository).getAuditLogs(PageRequest.of(0, 20), filter).stream()
                .map(AuditLog::getTargetIdentifier)
                .toList();
    }

    @Test
    void theSearchMatchesTheAdminTheTargetOrTheDetailsInAnyCase() {
        assertThat(targets(new AuditLogFilter(null, "admin@HRVA", null, null))).containsExactly("287");
        assertThat(targets(new AuditLogFilter(null, "FINDFAVORITE", null, null))).containsExactly("findfavoritedate.life");
        assertThat(targets(new AuditLogFilter(null, "phishing", null, null))).containsExactly("287");
        assertThat(targets(new AuditLogFilter(null, "domainrule", null, null))).containsExactly("findfavoritedate.life");
        assertThat(targets(new AuditLogFilter(null, "nobody", null, null))).isEmpty();
    }

    @Test
    void anUnderscoreAPercentSignOrABackslashIsNotAWildcard() {
        assertThat(targets(new AuditLogFilter(null, "%_", null, null))).containsExactly("50%_off");
        assertThat(targets(new AuditLogFilter(null, "2_7", null, null))).isEmpty();
        assertThat(targets(new AuditLogFilter(null, "\\", null, null))).isEmpty();
    }

    @Test
    void theDatesKeepWholeDaysAtBothEnds() {
        assertThat(targets(new AuditLogFilter(null, null, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 23)))).containsExactly("288", "287");
        assertThat(targets(new AuditLogFilter(null, null, LocalDate.of(2026, 10, 24), null))).containsExactly("50%_off");
        assertThat(targets(new AuditLogFilter(null, null, null, LocalDate.of(2026, 10, 1)))).containsExactly("findfavoritedate.life");
    }

    @Test
    void theActionTheSearchAndTheDatesCombine() {
        assertThat(targets(new AuditLogFilter(AuditAction.REVOKE_URL, null, null, null))).containsExactly("50%_off", "287");
        assertThat(targets(new AuditLogFilter(AuditAction.REVOKE_URL, "karlo", null, null))).containsExactly("50%_off");
        assertThat(targets(new AuditLogFilter(AuditAction.REVOKE_URL, null, null, LocalDate.of(2026, 10, 23)))).containsExactly("287");
    }

    @Test
    void theLogReadsOldestFirstWhenAsked() {
        final var oldestFirst = new DefaultAuditLogService(auditLogRepository)
                .getAuditLogs(PageRequest.of(0, 2, Sort.by(Sort.Order.asc("performedAt"))), AuditLogFilter.NONE);

        assertThat(oldestFirst.getTotalElements()).isEqualTo(4);
        assertThat(oldestFirst.getContent()).extracting(AuditLog::getTargetIdentifier).containsExactly("findfavoritedate.life", "287");
        assertThat(auditLogRepository.findAll(AuditLogSpecification.hasAction(AuditAction.RESOLVE_REVIEW), PageRequest.of(0, 20, NEWEST_FIRST)))
                .extracting(AuditLog::getTargetIdentifier).containsExactly("288");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = AuditLogRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AuditLogRepository.class))
    static class TestApplication {
    }

}
