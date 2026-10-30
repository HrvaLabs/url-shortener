package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.dto.ReviewEntryResponse;
import cc.hrva.urlshortener.dto.AdminUserResponse;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.UserSignInMethod;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.specification.UrlSpecification;
import cc.hrva.urlshortener.repository.specification.UserSpecification;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.impl.DefaultReviewQueueService;
import cc.hrva.urlshortener.service.impl.DefaultSignInMethodService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Transactional
@SpringBootTest(
        classes = AdminPageQueriesTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.datasource.url=jdbc:h2:mem:admin-page-queries;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AdminPageQueriesTest {

    private static final int OWNERS = 5;
    private static final int LINKS = 12;
    private static final Sort NEWEST_USERS_FIRST = Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id"));

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private LinkReviewRepository linkReviewRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSignInMethodRepository userSignInMethodRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        final var owners = IntStream.range(0, OWNERS).mapToObj(this::owner).toList();
        IntStream.range(0, LINKS).forEach(index -> {
            final var url = Url.builder().shortUrl("s" + index).longUrl("https://site" + index + ".example/login")
                    .owner(index % 4 == 3 ? null : owners.get(index % OWNERS)).tags(Set.of("tag" + index, "shared")).build();
            entityManager.persist(url);
            entityManager.persist(LinkReview.builder().url(url).reasons("login or verification lure").flaggedAt(LocalDateTime.now()).build());
        });
        entityManager.flush();
        entityManager.clear();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void aReviewQueuePageLoadsItsLinksAndOwnersWithoutAQueryPerRow() {
        final var scammerSignalService = mock(ScammerSignalService.class);
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of());
        final var reviewQueueService = new DefaultReviewQueueService(linkReviewRepository, urlRepository, scammerSignalService, Clock.systemUTC(),
                mock(ApplicationEventPublisher.class));

        final var entries = reviewQueueService.openEntries(PageRequest.of(0, 10), null, null, null);

        assertThat(entries.getContent()).hasSize(10);
        assertThat(entries.getTotalElements()).isEqualTo(LINKS);
        assertThat(entries.getContent()).filteredOn(entry -> entry.ownerEmail() != null).isNotEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void aReviewQueuePageLoadsTheDomainsOfItsLinksWithoutAnotherQuery() {
        onDomain(domain("go.example.com"), "s11", "s10");
        onDomain(domain("go.example.org"), "s9");
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        final var scammerSignalService = mock(ScammerSignalService.class);
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of());
        final var reviewQueueService = new DefaultReviewQueueService(linkReviewRepository, urlRepository, scammerSignalService, Clock.systemUTC(),
                mock(ApplicationEventPublisher.class));

        final var entries = reviewQueueService.openEntries(PageRequest.of(0, 10), null, null, null);

        assertThat(entries.getContent()).extracting(ReviewEntryResponse::domain).contains("go.example.com", "go.example.org");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void anAdminUrlPageLoadsItsOwnersAndTagsWithoutAQueryPerRow() {
        final var page = urlRepository.findAll(PageRequest.of(0, 10, Sort.by(Sort.Order.asc("owner.email").nullsLast())))
                .map(UrlResponse::from);

        assertThat(page.getContent()).hasSize(10);
        assertThat(page.getContent()).allSatisfy(row -> assertThat(row.tags()).contains("shared"));
        assertThat(page.getContent()).filteredOn(row -> row.ownerEmail() != null).isNotEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void aPageOfLinksStillArrivesWithItsTagsLoadedForCallersOutsideATransaction() {
        final var page = urlRepository.findAll(PageRequest.of(0, 10));

        assertThat(page.getContent()).allSatisfy(url -> {
            assertThat(Hibernate.isInitialized(url.getOwner())).isTrue();
            assertThat(Hibernate.isInitialized(url.getTags())).isTrue();
        });
    }

    @Test
    void aFilteredAdminUrlPageLoadsItsOwnersAndTagsWithoutAQueryPerRow() {
        final var page = urlRepository.findAll(UrlSpecification.searchIncludingOwner("site"), PageRequest.of(0, 10)).map(UrlResponse::from);

        assertThat(page.getContent()).hasSize(10);
        assertThat(page.getContent()).allSatisfy(row -> assertThat(row.tags()).contains("shared"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void anAdminUrlPageLoadsTheDomainsOfItsLinksWithoutAQueryPerDomain() {
        onDomain(domain("go.example.com"), "s0", "s1");
        onDomain(domain("go.example.org"), "s2", "s3");
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        final var page = urlRepository.findAll(PageRequest.of(0, 10, Sort.by("shortUrl")));

        assertThat(page.getContent()).extracting(url -> url.getDomain() == null ? null : url.getDomain().getHostname())
                .contains("go.example.com", "go.example.org");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void anOwnersLinksLoadTheirDomainsInOneBatch() {
        onDomain(domain("a.example.com"), "s0");
        onDomain(domain("b.example.com"), "s5");
        onDomain(domain("c.example.com"), "s10");
        entityManager.flush();
        entityManager.clear();
        final var owner = userRepository.findAll().stream().filter(user -> user.getEmail().equals("owner0@example.com")).findFirst().orElseThrow();
        entityManager.clear();
        statistics.clear();

        final var links = urlRepository.findAllByOwner(owner, PageRequest.of(0, 10)).getContent();

        assertThat(links).extracting(url -> url.getDomain().getHostname()).containsExactlyInAnyOrder("a.example.com", "b.example.com", "c.example.com");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
    }

    @Test
    void everyLinkOfAnOwnerLoadsItsDomainWithoutAnotherQuery() {
        final var owner = userRepository.findAll().stream().filter(user -> user.getEmail().equals("owner0@example.com")).findFirst().orElseThrow();
        entityManager.clear();
        statistics.clear();
        assertThat(urlRepository.findByOwner(owner)).extracting(Url::getDomain).containsOnlyNulls();
        final var withoutDomains = statistics.getPrepareStatementCount();
        onDomain(domain("a.example.com"), "s0");
        onDomain(domain("b.example.com"), "s5");
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        final var links = urlRepository.findByOwner(owner);

        assertThat(links).extracting(url -> url.getDomain() == null ? null : url.getDomain().getHostname())
                .containsExactlyInAnyOrder("a.example.com", "b.example.com", null);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(withoutDomains).isEqualTo(2);
    }

    @Test
    void anAdminUserPageLoadsEveryUsersRolesInOneQuery() {
        final var json = JsonMapper.builder().build().writeValueAsString(userRepository.findAll(PageRequest.of(0, 10)).getContent());

        assertThat(json).contains("ROLE_USER");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    private CustomDomain domain(final String hostname) {
        final var domain = CustomDomain.builder().hostname(hostname).hostnameKey(hostname).status(CustomDomainStatus.ACTIVE)
                .reason("Menu links for my cafe").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        entityManager.persist(domain);
        return domain;
    }

    private void onDomain(final CustomDomain domain, final String... aliases) {
        entityManager.createQuery("update Url u set u.domain = :domain where u.shortUrl in :aliases")
                .setParameter("domain", domain)
                .setParameter("aliases", List.of(aliases))
                .executeUpdate();
    }

    @Test
    void anAdminUserPageLoadsEveryUsersSignInMethodsInOneQuery() {
        withPasswordAndGoogleForEveryUser();
        final var signInMethodService = new DefaultSignInMethodService(userRepository, userSignInMethodRepository);

        final var users = userRepository.findAll(PageRequest.of(0, 10, NEWEST_USERS_FIRST));
        final var signInMethods = signInMethodService.methodsOf(users.map(User::getId).getContent());
        final var json = JsonMapper.builder().build().writeValueAsString(users.map(user -> AdminUserResponse.from(user, signInMethods, false)).getContent());

        assertThat(json).contains("ROLE_USER", "\"method\":\"PASSWORD\"", "\"method\":\"GOOGLE\"");
        assertThat(signInMethods).hasSize(OWNERS).allSatisfy((userId, methods) -> assertThat(methods).hasSize(2));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void anAdminUserPageFilteredBySignInMethodLoadsEveryUsersSignInMethodsInOneQuery() {
        withPasswordAndGoogleForEveryUser();
        final var signInMethodService = new DefaultSignInMethodService(userRepository, userSignInMethodRepository);

        final var users = userRepository.findAll(UserSpecification.hasSignInMethod(SignInMethod.GOOGLE), PageRequest.of(0, 10, NEWEST_USERS_FIRST));
        final var signInMethods = signInMethodService.methodsOf(users.map(User::getId).getContent());
        final var json = JsonMapper.builder().build().writeValueAsString(users.map(user -> AdminUserResponse.from(user, signInMethods, false)).getContent());

        assertThat(users.getContent()).hasSize(OWNERS);
        assertThat(json).contains("ROLE_USER", "\"method\":\"PASSWORD\"", "\"method\":\"GOOGLE\"");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    private void withPasswordAndGoogleForEveryUser() {
        userRepository.findAll().forEach(user -> {
            entityManager.persist(UserSignInMethod.builder().userId(user.getId()).method(SignInMethod.PASSWORD)
                    .firstUsedAt(LocalDateTime.of(2026, 1, 1, 8, 0)).lastUsedAt(LocalDateTime.of(2026, 10, 1, 8, 0)).build());
            entityManager.persist(UserSignInMethod.builder().userId(user.getId()).method(SignInMethod.GOOGLE)
                    .firstUsedAt(LocalDateTime.of(2026, 2, 1, 8, 0)).lastUsedAt(LocalDateTime.of(2026, 10, 2, 8, 0)).build());
        });
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
    }

    private User owner(final int index) {
        final var role = Authorities.builder().id(index + 1L).name("ROLE_USER").active(true).build();
        entityManager.persist(role);
        final var user = User.builder().email("owner" + index + "@example.com").password("correct horse battery").apiKeySlots(4L)
                .authorities(List.of(role)).build();
        entityManager.persist(user);
        return user;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UrlRepository.class, LinkReviewRepository.class, UserRepository.class,
                    UserSignInMethodRepository.class}))
    static class TestApplication {
    }

}
