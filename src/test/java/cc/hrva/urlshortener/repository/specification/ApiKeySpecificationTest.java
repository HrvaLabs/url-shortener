package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
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
        classes = ApiKeySpecificationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:api-key-specification;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ApiKeySpecificationTest {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id"));

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        final var ana = persistUser("Ana.Horvat@Example.com");
        final var marko = persistUser("marko@studio.hr");
        persistKey(ana, "3f2b9c1e-0000-4000-8000-000000000001", true, 12, 1);
        persistKey(ana, "a1b2c3d4-0000-4000-8000-000000000002", false, 1000, 2);
        persistKey(marko, "a1b2ffff-0000-4000-8000-000000000003", true, 40, 3);
        persistKey(marko, "50_off%0-0000-4000-8000-000000000004", false, 0, 4);
        entityManager.flush();
        entityManager.clear();
    }

    private User persistUser(final String email) {
        final var user = User.builder().email(email).password("password123").apiKeySlots(3L).active(true).build();
        entityManager.persist(user);
        return user;
    }

    private void persistKey(final User owner, final String key, final boolean active, final long callsUsed, final int day) {
        final var apiKey = ApiKey.builder().key(key).owner(owner).apiCallsLimit(1000L).expirationDate(LocalDateTime.of(2027, 1, 10 - day, 0, 0)).build();
        entityManager.persist(apiKey);
        entityManager.flush();
        apiKey.setCreateDate(LocalDateTime.of(2026, 10, day, 9, 0));
        apiKey.setActive(active);
        apiKey.setApiCallsUsed(callsUsed);
    }

    private List<String> keys(final Specification<ApiKey> spec, final Sort sort) {
        return apiKeyRepository.findAll(spec, PageRequest.of(0, 20, sort)).stream().map(apiKey -> apiKey.getKey().substring(0, 8)).toList();
    }

    @Test
    void matchesAPartOfTheOwnerEmailInAnyCase() {
        assertThat(keys(ApiKeySpecification.searchMatches("ana.HORVAT@"), NEWEST_FIRST)).containsExactly("a1b2c3d4", "3f2b9c1e");
        assertThat(keys(ApiKeySpecification.searchMatches("studio"), NEWEST_FIRST)).containsExactly("50_off%0", "a1b2ffff");
    }

    @Test
    void matchesTheStartOfTheKeyOnly() {
        assertThat(keys(ApiKeySpecification.searchMatches("A1B2"), NEWEST_FIRST)).containsExactly("a1b2ffff", "a1b2c3d4");
        assertThat(keys(ApiKeySpecification.searchMatches("a1b2c"), NEWEST_FIRST)).containsExactly("a1b2c3d4");
        assertThat(keys(ApiKeySpecification.searchMatches("c3d4"), NEWEST_FIRST)).isEmpty();
    }

    @Test
    void anUnderscoreAPercentSignOrABackslashIsNotAWildcard() {
        assertThat(keys(ApiKeySpecification.searchMatches("50_off%"), NEWEST_FIRST)).containsExactly("50_off%0");
        assertThat(keys(ApiKeySpecification.searchMatches("5_"), NEWEST_FIRST)).isEmpty();
        assertThat(keys(ApiKeySpecification.searchMatches("%"), NEWEST_FIRST)).isEmpty();
        assertThat(keys(ApiKeySpecification.searchMatches("\\"), NEWEST_FIRST)).isEmpty();
    }

    @Test
    void theActiveFilterKeepsActiveOrInactiveKeys() {
        assertThat(keys(ApiKeySpecification.hasActive(true), NEWEST_FIRST)).containsExactly("a1b2ffff", "3f2b9c1e");
        assertThat(keys(ApiKeySpecification.hasActive(false).and(ApiKeySpecification.searchMatches("ana")), NEWEST_FIRST)).containsExactly("a1b2c3d4");
    }

    @Test
    void keysSortByCallsUsedOrByExpiry() {
        assertThat(keys(Specification.unrestricted(), Sort.by(Sort.Order.desc("apiCallsUsed"))))
                .containsExactly("a1b2c3d4", "a1b2ffff", "3f2b9c1e", "50_off%0");
        assertThat(keys(Specification.unrestricted(), Sort.by(Sort.Order.asc("expirationDate"))))
                .containsExactly("50_off%0", "a1b2ffff", "a1b2c3d4", "3f2b9c1e");
    }

    @Test
    void aPageCarriesTheOwnersWithTheTotal() {
        final var page = apiKeyRepository.findAll(Specification.unrestricted(), PageRequest.of(1, 3, NEWEST_FIRST));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(apiKey -> apiKey.getOwner().getEmail()).containsExactly("Ana.Horvat@Example.com");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = ApiKeyRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ApiKeyRepository.class))
    static class TestApplication {
    }

}
