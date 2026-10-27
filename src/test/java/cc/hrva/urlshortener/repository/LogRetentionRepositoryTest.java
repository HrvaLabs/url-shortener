package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.Url;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = LogRetentionRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:log-retention;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class LogRetentionRepositoryTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 7, 29, 3, 50);

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void emptyTheLogs() {
        jdbcTemplate.update("delete from email_log");
        jdbcTemplate.update("delete from audit_log");
    }

    @Test
    void emailsLoggedBeforeTheCutoffAreDeletedAndLaterOnesStay() {
        emailLogged("old@example.com", CUTOFF.minusDays(1));
        emailLogged("edge@example.com", CUTOFF.minusNanos(1_000));
        emailLogged("at-cutoff@example.com", CUTOFF);
        emailLogged("recent@example.com", CUTOFF.plusDays(1));

        assertThat(emailLogRepository.deleteByCreatedAtBefore(CUTOFF)).isEqualTo(2);

        assertThat(jdbcTemplate.queryForList("select recipient from email_log order by recipient", String.class))
                .containsExactly("at-cutoff@example.com", "recent@example.com");
    }

    @Test
    void auditRowsBeforeTheCutoffAreDeletedAndLaterOnesStay() {
        audited("old", CUTOFF.minusMonths(2));
        audited("edge", CUTOFF.minusNanos(1_000));
        audited("at-cutoff", CUTOFF);
        audited("recent", CUTOFF.plusDays(1));

        assertThat(auditLogRepository.deleteByPerformedAtBefore(CUTOFF)).isEqualTo(2);

        assertThat(jdbcTemplate.queryForList("select target_identifier from audit_log order by target_identifier", String.class))
                .containsExactly("at-cutoff", "recent");
    }

    @Test
    void emptyLogsDeleteNothing() {
        assertThat(emailLogRepository.deleteByCreatedAtBefore(CUTOFF)).isZero();
        assertThat(auditLogRepository.deleteByPerformedAtBefore(CUTOFF)).isZero();
    }

    private void emailLogged(final String recipient, final LocalDateTime createdAt) {
        jdbcTemplate.update("insert into email_log (recipient, subject, status, created_at) values (?, 'Subject', 'SENT', ?)",
                recipient, createdAt);
    }

    private void audited(final String target, final LocalDateTime performedAt) {
        jdbcTemplate.update("""
                insert into audit_log (performed_by, action, target_type, target_identifier, performed_at, failed)
                values ('admin@localhost', 'UPDATE_URL', 'URL', ?, ?, false)""", target, performedAt);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {EmailLogRepository.class, AuditLogRepository.class}))
    static class TestApplication {
    }

}
