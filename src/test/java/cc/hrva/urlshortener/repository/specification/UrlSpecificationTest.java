package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import jakarta.persistence.EntityManager;
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
        classes = UrlSpecificationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:url-specification;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class UrlSpecificationTest {

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        final var phisher = persistUser("raulsado50w@gmail.com");
        final var owner = persistUser("karlo@hrva.cc");
        persistUrl("njuskaio_verify", "https://njuskaio.cam/a/MAUB3", phisher);
        persistUrl("gableci", "https://gableci.hrva.cc", owner);
        persistUrl("v0DB3", "https://findfavoritedate.life/?u=f6yp608", null);
        entityManager.flush();
    }

    private User persistUser(final String email) {
        final var user = User.builder().email(email).password("password123").apiKeySlots(1L).build();
        entityManager.persist(user);
        return user;
    }

    private void persistUrl(final String shortUrl, final String longUrl, final User owner) {
        entityManager.persist(Url.builder().shortUrl(shortUrl).longUrl(longUrl).owner(owner).build());
    }

    private List<String> shortUrls(final Specification<Url> spec) {
        return urlRepository.findAll(spec, Sort.by("shortUrl")).stream().map(Url::getShortUrl).toList();
    }

    @Test
    void adminSearchMatchesTheOwnersEmailIgnoringCase() {
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("RAULSADO50W"))).containsExactly("njuskaio_verify");
    }

    @Test
    void adminSearchStillMatchesShortAndLongUrlsIncludingAnonymousLinks() {
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("gableci"))).containsExactly("gableci");
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("findfavoritedate"))).containsExactly("v0DB3");
    }

    @Test
    void anonymousFilterKeepsOnlyLinksWithoutAnOwner() {
        assertThat(shortUrls(UrlSpecification.hasNoOwner())).containsExactly("v0DB3");
    }

    @Test
    void sortingByOwnerKeepsAnonymousLinksInThePage() {
        final var page = urlRepository.findAll(
                UrlSpecification.searchIncludingOwner("https"),
                PageRequest.of(0, 10, Sort.by(Sort.Order.asc("owner.email"))));

        final var order = page.getContent().stream().map(Url::getShortUrl).toList();
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(order).containsExactlyInAnyOrder("gableci", "njuskaio_verify", "v0DB3");
        assertThat(order.indexOf("gableci")).isLessThan(order.indexOf("njuskaio_verify"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UrlRepository.class))
    static class TestApplication {
    }

}
