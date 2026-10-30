package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.BioPageReviewRepository;
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
        classes = BioPageReviewSpecificationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:bio-page-review-specification;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class BioPageReviewSpecificationTest {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("flaggedAt"), Sort.Order.desc("id"));

    @Autowired
    private BioPageReviewRepository bioPageReviewRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        persistReview("Ana.Horvat@Example.com", "ana", "Ana Horvat", 1, false);
        persistReview("marko@studio.hr", "paypal_help", "PayPal Support", 2, false);
        persistReview("petra@example.org", null, "Petra 100%", 3, false);
        persistReview("ivana@example.com", "ivana", "Ivana", 4, true);
        entityManager.flush();
        entityManager.clear();
    }

    private void persistReview(final String email, final String handle, final String displayName, final int day, final boolean resolved) {
        final var owner = User.builder().email(email).password("password123").apiKeySlots(1L).active(true).build();
        entityManager.persist(owner);
        final var page = BioPage.builder().owner(owner).handle(handle).displayName(displayName).build();
        entityManager.persist(page);
        entityManager.persist(BioPageReview.builder().page(page).reasons("visitor report: spam").flaggedAt(LocalDateTime.of(2026, 10, day, 9, 0))
                .resolvedAt(resolved ? LocalDateTime.of(2026, 10, day, 10, 0) : null).systemFlagged(false).build());
    }

    private List<String> names(final Specification<BioPageReview> spec, final Sort sort) {
        return bioPageReviewRepository.findAll(BioPageReviewSpecification.isOpen().and(spec), PageRequest.of(0, 20, sort)).stream()
                .map(review -> review.getPage().getDisplayName())
                .toList();
    }

    @Test
    void openKeepsOnlyUnresolvedReviewsNewestFirst() {
        assertThat(names(Specification.unrestricted(), NEWEST_FIRST)).containsExactly("Petra 100%", "PayPal Support", "Ana Horvat");
    }

    @Test
    void theSearchMatchesTheHandleTheNameOrTheOwnerEmailInAnyCase() {
        assertThat(names(BioPageReviewSpecification.searchMatches("PAYPAL_"), NEWEST_FIRST)).containsExactly("PayPal Support");
        assertThat(names(BioPageReviewSpecification.searchMatches("ana horvat"), NEWEST_FIRST)).containsExactly("Ana Horvat");
        assertThat(names(BioPageReviewSpecification.searchMatches("@example."), NEWEST_FIRST)).containsExactly("Petra 100%", "Ana Horvat");
        assertThat(names(BioPageReviewSpecification.searchMatches("ivana"), NEWEST_FIRST)).isEmpty();
    }

    @Test
    void anUnderscoreAPercentSignOrABackslashIsNotAWildcard() {
        assertThat(names(BioPageReviewSpecification.searchMatches("l_h"), NEWEST_FIRST)).containsExactly("PayPal Support");
        assertThat(names(BioPageReviewSpecification.searchMatches("a_a"), NEWEST_FIRST)).isEmpty();
        assertThat(names(BioPageReviewSpecification.searchMatches("%"), NEWEST_FIRST)).containsExactly("Petra 100%");
        assertThat(names(BioPageReviewSpecification.searchMatches("\\"), NEWEST_FIRST)).isEmpty();
    }

    @Test
    void reviewsReadOldestFirstWhenAskedAndPageWithTheirOwners() {
        assertThat(names(Specification.unrestricted(), Sort.by(Sort.Order.asc("flaggedAt"), Sort.Order.desc("id"))))
                .containsExactly("Ana Horvat", "PayPal Support", "Petra 100%");
        final var page = bioPageReviewRepository.findAll(BioPageReviewSpecification.isOpen(), PageRequest.of(1, 2, NEWEST_FIRST));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(review -> review.getPage().getOwner().getEmail()).containsExactly("Ana.Horvat@Example.com");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = BioPageReviewRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BioPageReviewRepository.class))
    static class TestApplication {
    }

}
