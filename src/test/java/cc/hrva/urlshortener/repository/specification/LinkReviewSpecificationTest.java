package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = LinkReviewSpecificationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:link-review-specification;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class LinkReviewSpecificationTest {

    @Autowired
    private LinkReviewRepository linkReviewRepository;

    @Autowired
    private EntityManager entityManager;

    private final Map<String, Url> links = new HashMap<>();

    @BeforeEach
    void setUp() {
        persistReview("v0DB3", "high-risk domain ending .life\nLogin or verification lure", false);
        persistReview("njuskaio_verify", "domain registered in the last 30 days\nimitates njuskalo", false);
        persistReview("gableci", "domain registered in the last 30 days", true);
        entityManager.flush();
    }

    private void persistReview(final String shortUrl, final String reasons, final boolean resolved) {
        final var url = Url.builder().shortUrl(shortUrl).longUrl("https://" + shortUrl + ".example").build();
        entityManager.persist(url);
        links.put(shortUrl, url);
        entityManager.persist(LinkReview.builder()
                .url(url)
                .reasons(reasons)
                .flaggedAt(LocalDateTime.now())
                .resolvedAt(resolved ? LocalDateTime.now() : null)
                .resolution(resolved ? ReviewResolution.OK : null)
                .build());
    }

    private List<String> shortUrls(final Specification<LinkReview> spec) {
        return linkReviewRepository.findAll(spec, Sort.by("url.shortUrl")).stream()
                .map(review -> review.getUrl().getShortUrl())
                .toList();
    }

    @Test
    void openKeepsOnlyUnresolvedEntries() {
        assertThat(shortUrls(LinkReviewSpecification.isOpen())).containsExactly("njuskaio_verify", "v0DB3");
    }

    @Test
    void theReasonFilterMatchesAnyPartOfAnyReasonIgnoringCase() {
        assertThat(shortUrls(LinkReviewSpecification.isOpen().and(LinkReviewSpecification.reasonContains("YOUNG"))))
                .isEmpty();
        assertThat(shortUrls(LinkReviewSpecification.isOpen().and(LinkReviewSpecification.reasonContains("REGISTERED in"))))
                .containsExactly("njuskaio_verify");
        assertThat(shortUrls(LinkReviewSpecification.isOpen().and(LinkReviewSpecification.reasonContains("login"))))
                .containsExactly("v0DB3");
    }

    @Test
    void theAgeFilterKeepsOnlyEntriesWhoseLinkWasCreatedBeforeTheCutoff() {
        final var cutoff = LocalDateTime.now().minusDays(7);
        links.get("v0DB3").setCreateDate(cutoff.minusDays(1));
        links.get("njuskaio_verify").setCreateDate(cutoff.plusHours(1));
        links.get("gableci").setCreateDate(cutoff.minusDays(30));
        entityManager.flush();

        assertThat(shortUrls(LinkReviewSpecification.isOpen().and(LinkReviewSpecification.linkCreatedBefore(cutoff))))
                .containsExactly("v0DB3");
        assertThat(shortUrls(LinkReviewSpecification.isOpen()
                .and(LinkReviewSpecification.linkCreatedBefore(cutoff))
                .and(LinkReviewSpecification.reasonContains("login"))))
                .containsExactly("v0DB3");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = LinkReviewRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = LinkReviewRepository.class))
    static class TestApplication {
    }

}
