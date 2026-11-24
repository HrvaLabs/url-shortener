package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.UserSignInMethod;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.OwnerUrlTotals;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
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
        classes = UserSpecificationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:user-specification;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class UserSpecificationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        persistUser("karlo@hrva.cc");
        persistUser("a%%b@example.com");
        persistUser("a%b@example.com");
        persistUser("a_b@example.com");
        persistUser("axb@example.com");
        entityManager.flush();
    }

    private void persistUser(final String email) {
        entityManager.persist(User.builder().email(email).password("password123").apiKeySlots(1L).build());
    }

    private List<String> emails(final Specification<User> spec) {
        return userRepository.findAll(spec, Sort.by("email")).stream().map(User::getEmail).toList();
    }

    @Test
    void searchMatchesAnyPartOfTheEmailIgnoringCase() {
        assertThat(emails(UserSpecification.search("KARLO@HRVA"))).containsExactly("karlo@hrva.cc");
        assertThat(emails(UserSpecification.search("example.com"))).containsExactly("a%%b@example.com", "a%b@example.com", "a_b@example.com", "axb@example.com");
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        assertThat(emails(UserSpecification.search("%%"))).containsExactly("a%%b@example.com");
        assertThat(emails(UserSpecification.search("%"))).containsExactly("a%%b@example.com", "a%b@example.com");
        assertThat(emails(UserSpecification.search("a_b"))).containsExactly("a_b@example.com");
        assertThat(emails(UserSpecification.search("_"))).containsExactly("a_b@example.com");
        assertThat(emails(UserSpecification.search("\\%"))).isEmpty();
    }

    @Test
    void theSignInMethodFilterKeepsAccountsThatUsedTheMethodAtLeastOnce() {
        final var karlo = userRepository.findAll(UserSpecification.search("karlo@hrva.cc")).getFirst();
        final var percent = userRepository.findAll(UserSpecification.search("a%b@")).getFirst();
        recordUse(karlo, SignInMethod.PASSWORD);
        recordUse(karlo, SignInMethod.GITHUB);
        recordUse(percent, SignInMethod.GITHUB);
        entityManager.flush();

        assertThat(emails(UserSpecification.hasSignInMethod(SignInMethod.GITHUB))).containsExactly("a%b@example.com", "karlo@hrva.cc");
        assertThat(emails(UserSpecification.hasSignInMethod(SignInMethod.PASSWORD))).containsExactly("karlo@hrva.cc");
        assertThat(emails(UserSpecification.hasSignInMethod(SignInMethod.PASSKEY))).isEmpty();
        assertThat(emails(UserSpecification.search("karlo").and(UserSpecification.hasSignInMethod(SignInMethod.GITHUB))))
                .containsExactly("karlo@hrva.cc");
    }

    private User user(final String email) {
        return userRepository.findAll(UserSpecification.search(email)).getFirst();
    }

    private void link(final User owner, final String alias, final long visits) {
        final var url = Url.builder().shortUrl(alias).longUrl("https://" + alias + ".example/").owner(owner).build();
        entityManager.persist(url);
        url.setVisits(visits);
        entityManager.flush();
    }

    private void givenLinksAndClicks() {
        link(user("karlo@hrva.cc"), "k1", 10);
        link(user("karlo@hrva.cc"), "k2", 5);
        link(user("a%b@"), "p1", 100);
        link(user("axb@"), "x1", 15);
        link(user("axb@"), "x2", 0);
        link(user("axb@"), "x3", 0);
        entityManager.clear();
    }

    private List<String> emailsBy(final Specification<User> spec, final int page, final int size) {
        return userRepository.findAll(spec, PageRequest.of(page, size)).stream().map(User::getEmail).toList();
    }

    @Test
    void theLinkAndClickTotalsAreOneGroupedReadWithZerosLeftOut() {
        givenLinksAndClicks();
        final var ids = List.of(user("karlo@hrva.cc").getId(), user("a%b@").getId(), user("axb@").getId(), user("a_b@").getId());

        final var totals = urlRepository.totalsOfOwners(ids);

        assertThat(totals).extracting(OwnerUrlTotals::ownerId, OwnerUrlTotals::links, OwnerUrlTotals::clicks)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(user("karlo@hrva.cc").getId(), 2L, 15L),
                        org.assertj.core.groups.Tuple.tuple(user("a%b@").getId(), 1L, 100L),
                        org.assertj.core.groups.Tuple.tuple(user("axb@").getId(), 3L, 15L));
    }

    @Test
    void sortingByLinksPutsTheMostLinksFirstAndTheNewestAccountFirstOnTies() {
        givenLinksAndClicks();

        assertThat(emailsBy(UserSpecification.orderedBy(Sort.by(Sort.Order.desc("links"), Sort.Order.desc("id"))), 0, 10))
                .containsExactly("axb@example.com", "karlo@hrva.cc", "a%b@example.com", "a_b@example.com", "a%%b@example.com");
        assertThat(emailsBy(UserSpecification.orderedBy(Sort.by(Sort.Order.asc("links"), Sort.Order.desc("id"))), 0, 10))
                .containsExactly("a_b@example.com", "a%%b@example.com", "a%b@example.com", "karlo@hrva.cc", "axb@example.com");
    }

    @Test
    void sortingByClicksCountsAccountsWithoutLinksAsZero() {
        givenLinksAndClicks();

        assertThat(emailsBy(UserSpecification.orderedBy(Sort.by(Sort.Order.desc("clicks"), Sort.Order.desc("id"))), 0, 10))
                .containsExactly("a%b@example.com", "axb@example.com", "karlo@hrva.cc", "a_b@example.com", "a%%b@example.com");
        assertThat(emailsBy(UserSpecification.orderedBy(Sort.by(Sort.Order.asc("clicks"), Sort.Order.desc("id"))), 0, 10))
                .containsExactly("a_b@example.com", "a%%b@example.com", "axb@example.com", "karlo@hrva.cc", "a%b@example.com");
    }

    @Test
    void theCountSortKeepsPagingTheTotalAndTheOtherFilters() {
        givenLinksAndClicks();
        final var byLinks = UserSpecification.orderedBy(Sort.by(Sort.Order.desc("links"), Sort.Order.desc("id")));

        assertThat(emailsBy(byLinks, 1, 2)).containsExactly("a%b@example.com", "a_b@example.com");
        final var filtered = UserSpecification.search("example.com").and(byLinks);
        assertThat(emailsBy(filtered, 0, 2)).containsExactly("axb@example.com", "a%b@example.com");
        assertThat(userRepository.findAll(filtered, PageRequest.of(0, 2)).getTotalElements()).isEqualTo(4);
    }

    private void recordUse(final User user, final SignInMethod method) {
        final var now = LocalDateTime.of(2026, 10, 29, 9, 0);
        entityManager.persist(UserSignInMethod.builder().userId(user.getId()).method(method).firstUsedAt(now).lastUsedAt(now).build());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UserRepository.class, UrlRepository.class}))
    static class TestApplication {
    }

}
