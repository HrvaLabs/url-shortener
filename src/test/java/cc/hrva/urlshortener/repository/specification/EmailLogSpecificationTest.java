package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.EmailStatus;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.service.impl.DefaultEmailLogService;
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
        classes = EmailLogSpecificationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:email-log-specification;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class EmailLogSpecificationTest {

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        persistEmail("Ana.Horvat@Example.com", "Confirm your email", EmailLog.STATUS_SENT, 1);
        persistEmail("marko@studio.hr", "Your link was blocked", EmailLog.STATUS_FAILED, 2);
        persistEmail("ana.horvat@example.com", "Weekly summary: 100% of links up", EmailLog.STATUS_SENT, 3);
        persistEmail("petra@example.org", "Your_link expires soon", EmailLog.STATUS_FAILED, 4);
        entityManager.flush();
        entityManager.clear();
    }

    private void persistEmail(final String recipient, final String subject, final String status, final int day) {
        final var email = EmailLog.builder().sender("no-reply@hrva.cc").recipient(recipient).subject(subject).status(status).build();
        entityManager.persist(email);
        entityManager.flush();
        email.setCreatedAt(LocalDateTime.of(2026, 10, day, 9, 0));
    }

    private List<String> subjects(final EmailStatus status, final String search, final Sort sort) {
        return new DefaultEmailLogService(emailLogRepository).emails(status, search, PageRequest.of(0, 20, sort)).stream()
                .map(EmailLog::getSubject)
                .toList();
    }

    @Test
    void theSearchMatchesTheRecipientOrTheSubjectInAnyCase() {
        assertThat(subjects(null, "ANA.horvat@", Sort.unsorted())).containsExactly("Weekly summary: 100% of links up", "Confirm your email");
        assertThat(subjects(null, "BLOCKED", Sort.unsorted())).containsExactly("Your link was blocked");
        assertThat(subjects(null, "nobody", Sort.unsorted())).isEmpty();
    }

    @Test
    void anUnderscoreAPercentSignOrABackslashIsNotAWildcard() {
        assertThat(subjects(null, "100%", Sort.unsorted())).containsExactly("Weekly summary: 100% of links up");
        assertThat(subjects(null, "your_", Sort.unsorted())).containsExactly("Your_link expires soon");
        assertThat(subjects(null, "\\", Sort.unsorted())).isEmpty();
    }

    @Test
    void theStatusAndTheSearchCombine() {
        assertThat(subjects(EmailStatus.FAILED, null, Sort.unsorted())).containsExactly("Your_link expires soon", "Your link was blocked");
        assertThat(subjects(EmailStatus.FAILED, "marko", Sort.unsorted())).containsExactly("Your link was blocked");
        assertThat(subjects(EmailStatus.SENT, "blocked", Sort.unsorted())).isEmpty();
    }

    @Test
    void theLogReadsOldestFirstWhenAsked() {
        assertThat(subjects(null, null, Sort.by(Sort.Order.asc("createdAt"))))
                .containsExactly("Confirm your email", "Your link was blocked", "Weekly summary: 100% of links up", "Your_link expires soon");
        assertThat(emailLogRepository.findAll(EmailLogSpecification.hasStatus(EmailLog.STATUS_SENT), PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(2);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = EmailLogRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = EmailLogRepository.class))
    static class TestApplication {
    }

}
