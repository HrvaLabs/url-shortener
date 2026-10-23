package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.specification.UrlSpecification;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.impl.DefaultReviewQueueService;
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

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private LinkReviewRepository linkReviewRepository;

    @Autowired
    private UserRepository userRepository;

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

        final var entries = reviewQueueService.openEntries(PageRequest.of(0, 10), null, null);

        assertThat(entries.getContent()).hasSize(10);
        assertThat(entries.getTotalElements()).isEqualTo(LINKS);
        assertThat(entries.getContent()).filteredOn(entry -> entry.ownerEmail() != null).isNotEmpty();
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
    void anAdminUserPageLoadsEveryUsersRolesInOneQuery() {
        final var json = JsonMapper.builder().build().writeValueAsString(userRepository.findAll(PageRequest.of(0, 10)).getContent());

        assertThat(json).contains("ROLE_USER");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
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
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UrlRepository.class, LinkReviewRepository.class, UserRepository.class}))
    static class TestApplication {
    }

}
