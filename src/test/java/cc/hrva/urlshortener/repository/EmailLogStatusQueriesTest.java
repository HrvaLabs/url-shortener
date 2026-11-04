package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.Url;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = EmailLogStatusQueriesTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:email-log-status;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class EmailLogStatusQueriesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 22, 0);

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void anEmptyLogHasNoLastSendAndNoFailures() {
        assertThat(emailLogRepository.findLastSentAt()).isEmpty();
        assertThat(emailLogRepository.countByStatusAndCreatedAtGreaterThanEqual(EmailLog.STATUS_FAILED, NOW.minusHours(24))).isZero();
    }

    @Test
    void theLastSendIsTheNewestSentAtWhateverItsStatusNow() {
        email(EmailLog.STATUS_SENT, NOW.minusDays(2), NOW.minusDays(2));
        email(EmailLog.STATUS_RESENT, NOW.minusHours(5), NOW.minusHours(5));
        email(EmailLog.STATUS_FAILED, NOW.minusHours(1), null);
        email(EmailLog.STATUS_SENDING, NOW.minusMinutes(1), null);

        assertThat(emailLogRepository.findLastSentAt()).contains(NOW.minusHours(5));
    }

    @Test
    void failuresCountOnlyTheLast24Hours() {
        email(EmailLog.STATUS_FAILED, NOW.minusHours(24), null);
        email(EmailLog.STATUS_FAILED, NOW.minusHours(2), null);
        email(EmailLog.STATUS_FAILED, NOW.minusHours(24).minusSeconds(1), null);
        email(EmailLog.STATUS_RESENT, NOW.minusHours(3), null);
        email(EmailLog.STATUS_SENT, NOW.minusHours(1), NOW.minusHours(1));

        assertThat(emailLogRepository.countByStatusAndCreatedAtGreaterThanEqual(EmailLog.STATUS_FAILED, NOW.minusHours(24))).isEqualTo(2);
    }

    private void email(final String status, final LocalDateTime createdAt, final LocalDateTime sentAt) {
        final var email = EmailLog.builder().sender("no-reply@hrva.cc").recipient("ana@example.com").subject("Subject").status(status).build();
        entityManager.persist(email);
        entityManager.flush();
        email.setCreatedAt(createdAt);
        email.setSentAt(sentAt);
        entityManager.flush();
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
