package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
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
    void deadDestinationFilter() {
        persistUrl("dead-active", "https://gone.example/a", null);
        persistUrl("dead-inactive", "https://gone.example/b", null);
        persistUrl("failing", "https://flaky.example/c", null);
        entityManager.flush();
        markDead("dead-active", LocalDateTime.of(2026, 10, 25, 2, 0), 3);
        markDead("dead-inactive", LocalDateTime.of(2026, 10, 20, 2, 0), 5);
        markDead("failing", null, 2);
        entityManager.createNativeQuery("update url set active = false where short_url = 'dead-inactive'").executeUpdate();
        entityManager.clear();

        assertThat(shortUrls(UrlSpecification.hasDeadDestination())).containsExactly("dead-active");
        assertThat(shortUrls(UrlSpecification.hasDeadDestination().and(UrlSpecification.hasNoOwner()))).containsExactly("dead-active");
    }

    private void markDead(final String shortUrl, final LocalDateTime deadSince, final int failStreak) {
        entityManager.createNativeQuery("update url set destination_dead_since = ?, destination_fail_streak = ? where short_url = ?")
                .setParameter(1, deadSince).setParameter(2, failStreak).setParameter(3, shortUrl).executeUpdate();
    }

    @Test
    void aTagFilterInCapitalsMatchesUnderATurkishServerLocale() {
        entityManager.persist(Url.builder().shortUrl("trip").longUrl("https://example.com/trip").tags(Set.of("istanbul")).build());
        entityManager.flush();
        final var serverLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(shortUrls(UrlSpecification.hasTag(" ISTANBUL "))).containsExactly("trip");
        } finally {
            Locale.setDefault(serverLocale);
        }
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
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        persistUrl("percent", "https://discount.example/100%25off", null);
        persistUrl("100%%off", "https://double.example/", null);
        persistUrl("underscore", "https://evil_login.com/verify", null);
        persistUrl("lookalike", "https://evilxlogin.com/verify", null);
        persistUrl("a\\b", "https://odd.example/", null);
        entityManager.flush();

        for (final var search : List.<Function<String, Specification<Url>>>of(UrlSpecification::searchIncludingOwner, UrlSpecification::search)) {
            assertThat(shortUrls(search.apply("%%"))).containsExactly("100%%off");
            assertThat(shortUrls(search.apply("%"))).containsExactly("100%%off", "percent");
            assertThat(shortUrls(search.apply("evil_login"))).containsExactly("underscore");
            assertThat(shortUrls(search.apply("_"))).containsExactly("njuskaio_verify", "underscore");
            assertThat(shortUrls(search.apply("a\\b"))).containsExactly("a\\b");
            assertThat(shortUrls(search.apply("\\"))).containsExactly("a\\b");
            assertThat(shortUrls(search.apply("\\%"))).isEmpty();
        }
    }

    @Test
    void adminSearchTreatsWildcardsInTheOwnersEmailAsPlainText() {
        persistUrl("owned", "https://plain.example/", persistUser("a_b@example.com"));
        persistUrl("other", "https://other.example/", persistUser("axb@example.com"));
        entityManager.flush();

        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("a_b@"))).containsExactly("owned");
    }

    @Test
    void adminSearchFindsBothSpellingsOfADomainFromEitherSpelling() {
        persistUrl("unicode", "https://čokolada.hr/torta", null);
        persistUrl("punycode", "https://xn--okolada-i6a.hr/kolac", null);
        entityManager.flush();

        for (final var search : List.of("čokolada.hr", "xn--okolada-i6a.hr", "ČOKOLADA.HR", "XN--OKOLADA-I6A.HR", "https://čokolada.hr/", "xn--okolada-i6a.hr/")) {
            assertThat(shortUrls(UrlSpecification.searchIncludingOwner(search))).as(search).containsExactly("punycode", "unicode");
        }
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("https://čokolada.hr/torta"))).containsExactly("unicode");
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("https://xn--okolada-i6a.hr/torta"))).containsExactly("unicode");
    }

    @Test
    void ownSearchFindsBothSpellingsOfADomainAmongTheOwnersLinksOnly() {
        final var owner = persistUser("owner@example.com");
        persistUrl("unicode", "https://čokolada.hr/torta", owner);
        persistUrl("punycode", "https://xn--okolada-i6a.hr/kolac", owner);
        persistUrl("someone", "https://čokolada.hr/x", persistUser("someone@example.com"));
        entityManager.flush();

        final Function<String, Specification<Url>> ownSearch = search -> UrlSpecification.hasOwner(owner).and(UrlSpecification.search(search));
        for (final var search : List.of("čokolada.hr", "xn--okolada-i6a.hr", "ČOKOLADA.HR", "XN--OKOLADA-I6A.HR", "https://čokolada.hr/", "xn--okolada-i6a.hr/")) {
            assertThat(shortUrls(ownSearch.apply(search))).as(search).containsExactly("punycode", "unicode");
        }
        assertThat(shortUrls(ownSearch.apply("https://xn--okolada-i6a.hr/torta"))).containsExactly("unicode");
        assertThat(shortUrls(ownSearch.apply("čokolada"))).containsExactly("unicode");
        assertThat(shortUrls(ownSearch.apply("xn--okolada"))).containsExactly("punycode");
    }

    @Test
    void adminSearchForAHostTypedHalfInEachSpellingFindsBothFullSpellings() {
        persistUrl("unicode", "https://čokolada.рф/torta", null);
        persistUrl("punycode", "https://xn--okolada-i6a.xn--p1ai/kolac", null);
        entityManager.flush();

        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("čokolada.xn--p1ai"))).containsExactly("punycode", "unicode");
    }

    @Test
    void adminSearchFindsAnOwnerEmailUnderEitherSpellingOfItsDomain() {
        persistUrl("owned", "https://plain.example/", persistUser("karlo@xn--okolada-i6a.hr"));
        entityManager.flush();

        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("karlo@čokolada.hr"))).containsExactly("owned");
    }

    @Test
    void adminSearchForAPlainAsciiDomainIsUnchanged() {
        persistUrl("unicode", "https://čokolada.hr/torta", null);
        persistUrl("punycode", "https://xn--okolada-i6a.hr/kolac", null);
        entityManager.flush();

        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("gableci.hrva.cc"))).containsExactly("gableci");
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("njuskaio.cam"))).containsExactly("njuskaio_verify");
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner(".hr/"))).containsExactly("punycode", "unicode");
    }

    @Test
    void adminSearchWithoutAHostIsUnchanged() {
        persistUrl("unicode", "https://čokolada.hr/torta", null);
        persistUrl("punycode", "https://xn--okolada-i6a.hr/kolac", null);
        entityManager.flush();

        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("čokolada"))).containsExactly("unicode");
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("xn--okolada"))).containsExactly("punycode");
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("torta"))).containsExactly("unicode");
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("gableci"))).containsExactly("gableci");
        assertThat(shortUrls(UrlSpecification.searchIncludingOwner("čokolada torta"))).isEmpty();
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

    @Test
    void ownerSortCanKeepAnonymousLinksLastInBothDirections() {
        for (final var direction : Sort.Direction.values()) {
            final var page = urlRepository.findAll(
                    UrlSpecification.searchIncludingOwner("https"),
                    PageRequest.of(0, 10, Sort.by(new Sort.Order(direction, "owner.email").nullsLast())));

            assertThat(page.getContent().getLast().getShortUrl()).as(direction.name()).isEqualTo("v0DB3");
        }
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
