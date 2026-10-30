package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.ReportedDomain;
import cc.hrva.urlshortener.model.Url;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
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
        classes = ReportedDomainRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:reported-domain;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ReportedDomainRepositoryTest {

    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 27, 9, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2026, 10, 30, 11, 0);

    @Autowired
    private ReportedDomainRepository reportedDomainRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void aFirstReportInsertsTheRow() {
        assertThat(reportedDomainRepository.upsert("omaehhm.com", "NameSilo, LLC", "abuse@namesilo.com", "karlo@hrva.cc", FIRST)).isEqualTo(1);

        assertThat(reload("omaehhm.com")).usingRecursiveComparison().isEqualTo(new ReportedDomain("omaehhm.com", "NameSilo, LLC", "abuse@namesilo.com",
                "karlo@hrva.cc", FIRST, null, null));
    }

    @Test
    void reportingAgainKeepsTheFirstReportAndItsChecksAndUpdatesOnlyTheContact() {
        reportedDomainRepository.upsert("omaehhm.com", "NameSilo, LLC", "abuse@namesilo.com", "karlo@hrva.cc", FIRST);
        reportedDomainRepository.markSuspended("omaehhm.com", FIRST.plusDays(1));

        assertThat(reportedDomainRepository.upsert("omaehhm.com", "NameSilo LLC", "abuse2@namesilo.com", "ana@hrva.cc", LATER)).isEqualTo(1);
        assertThat(reload("omaehhm.com")).usingRecursiveComparison().isEqualTo(new ReportedDomain("omaehhm.com", "NameSilo LLC", "abuse2@namesilo.com",
                "karlo@hrva.cc", FIRST, FIRST.plusDays(1), FIRST.plusDays(1)));

        reportedDomainRepository.upsert("omaehhm.com", null, null, "ana@hrva.cc", LATER);
        assertThat(reload("omaehhm.com")).extracting(ReportedDomain::getRegistrar, ReportedDomain::getAbuseEmail)
                .containsExactly("NameSilo LLC", "abuse2@namesilo.com");
    }

    @Test
    void aReportWithoutAContactKeepsNulls() {
        reportedDomainRepository.upsert("example.hr", null, null, "karlo@hrva.cc", FIRST);

        assertThat(reload("example.hr")).extracting(ReportedDomain::getRegistrar, ReportedDomain::getAbuseEmail).containsExactly(null, null);
    }

    @Test
    void recheckListsUnsuspendedDomainsReportedSinceTheCutoffInOrder() {
        reportedDomainRepository.upsert("b.com", null, null, "karlo@hrva.cc", FIRST);
        reportedDomainRepository.upsert("a.com", null, null, "karlo@hrva.cc", FIRST.plusHours(1));
        reportedDomainRepository.upsert("old.com", null, null, "karlo@hrva.cc", FIRST.minusSeconds(1));
        reportedDomainRepository.upsert("gone.com", null, null, "karlo@hrva.cc", LATER);
        reportedDomainRepository.markSuspended("gone.com", LATER);

        assertThat(reportedDomainRepository.findToRecheck(FIRST)).containsExactly("a.com", "b.com");
    }

    @Test
    void markingSuspendedHappensOnceAndCheckedOnlyTouchesTheGivenDomains() {
        reportedDomainRepository.upsert("a.com", null, null, "karlo@hrva.cc", FIRST);
        reportedDomainRepository.upsert("b.com", null, null, "karlo@hrva.cc", FIRST);

        assertThat(reportedDomainRepository.markSuspended("a.com", LATER)).isEqualTo(1);
        assertThat(reportedDomainRepository.markSuspended("a.com", LATER.plusDays(1))).isZero();
        assertThat(reportedDomainRepository.markChecked(List.of("b.com"), LATER)).isEqualTo(1);

        assertThat(reload("a.com")).extracting(ReportedDomain::getSuspendedAt, ReportedDomain::getLastCheckedAt).containsExactly(LATER, LATER);
        assertThat(reload("b.com")).extracting(ReportedDomain::getSuspendedAt, ReportedDomain::getLastCheckedAt).containsExactly(null, LATER);
    }

    private ReportedDomain reload(final String domain) {
        entityManager.flush();
        entityManager.clear();
        return reportedDomainRepository.findById(domain).orElseThrow();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ReportedDomainRepository.class))
    static class TestApplication {
    }

}
