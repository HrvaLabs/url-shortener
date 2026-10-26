package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.BioPageRepository;
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
        classes = BioPageSpecificationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:bio-page-specification;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class BioPageSpecificationTest {

    @Autowired
    private BioPageRepository bioPageRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        persistPage("ana@example.com", "ana", "Ana Horvat", false);
        persistPage("marko@studio.hr", "marko-photo", "Marko's Studio", true);
        persistPage("Ivana.Kovac@Example.com", null, "Ivana", true);
        persistPage("petra@example.org", "petra", "", false);
        entityManager.flush();
        entityManager.clear();
    }

    private void persistPage(final String email, final String handle, final String displayName, final boolean hidden) {
        final var owner = User.builder().email(email).password("password123").apiKeySlots(1L).active(true).build();
        entityManager.persist(owner);
        entityManager.persist(BioPage.builder()
                .owner(owner)
                .handle(handle)
                .displayName(displayName)
                .hiddenAt(hidden ? LocalDateTime.of(2026, 10, 26, 9, 0) : null)
                .hiddenReason(hidden ? "Spam" : null)
                .build());
    }

    private List<String> emails(final Specification<BioPage> spec) {
        return bioPageRepository.findAll(spec, PageRequest.of(0, 20, Sort.by("owner.email"))).stream()
                .map(page -> page.getOwner().getEmail())
                .toList();
    }

    @Test
    void matchesAPartOfTheHandle() {
        assertThat(emails(BioPageSpecification.searchMatches("ARKO-PH"))).containsExactly("marko@studio.hr");
        assertThat(emails(BioPageSpecification.searchMatches("petra"))).containsExactly("petra@example.org");
    }

    @Test
    void matchesTheDisplayNameInAnyCase() {
        assertThat(emails(BioPageSpecification.searchMatches("ana HORVAT"))).containsExactly("ana@example.com");
        assertThat(emails(BioPageSpecification.searchMatches("s studio"))).containsExactly("marko@studio.hr");
    }

    @Test
    void matchesTheOwnerEmailEvenWithoutAHandle() {
        assertThat(emails(BioPageSpecification.searchMatches("ivana.kovac@example"))).containsExactly("Ivana.Kovac@Example.com");
        assertThat(emails(BioPageSpecification.searchMatches("example."))).containsExactly("Ivana.Kovac@Example.com", "ana@example.com", "petra@example.org");
    }

    @Test
    void aSearchThatMatchesNothingFindsNothing() {
        assertThat(emails(BioPageSpecification.searchMatches("nobody"))).isEmpty();
    }

    @Test
    void anUnderscoreMatchesOnlyAnUnderscore() {
        persistPage("underscore@escape.test", "a_b", "", false);
        persistPage("hyphen@escape.test", "a-b", "", false);
        persistPage("letter@escape.test", "axb", "", false);
        entityManager.flush();

        assertThat(emails(BioPageSpecification.searchMatches("a_b"))).containsExactly("underscore@escape.test");
    }

    @Test
    void aPercentSignIsNotAWildcard() {
        assertThat(emails(BioPageSpecification.searchMatches("%"))).isEmpty();

        persistPage("percent@escape.test", "vegan", "100% vegan", false);
        entityManager.flush();

        assertThat(emails(BioPageSpecification.searchMatches("%"))).containsExactly("percent@escape.test");
    }

    @Test
    void aBackslashMatchesOnlyABackslash() {
        persistPage("backslash@escape.test", "slash", "a\\b", false);
        persistPage("plain@escape.test", "plain", "ab", false);
        entityManager.flush();

        assertThat(emails(BioPageSpecification.searchMatches("a\\b"))).containsExactly("backslash@escape.test");
    }

    @Test
    void isHiddenKeepsOnlyHiddenPages() {
        assertThat(emails(BioPageSpecification.isHidden())).containsExactly("Ivana.Kovac@Example.com", "marko@studio.hr");
        assertThat(emails(BioPageSpecification.isHidden().and(BioPageSpecification.searchMatches("studio")))).containsExactly("marko@studio.hr");
    }

    @Test
    void theOwnerComesWithThePageAndTheCountIsRight() {
        final var page = bioPageRepository.findAll(BioPageSpecification.searchMatches("example"), PageRequest.of(0, 2, Sort.by("owner.email")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allSatisfy(bioPage -> assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(bioPage, "owner")).isTrue());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = BioPageRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BioPageRepository.class))
    static class TestApplication {
    }

}
