package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CreatedVia;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.stream.IntStream;
import org.assertj.core.groups.Tuple;
import org.hibernate.Hibernate;
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
        classes = AccountQueriesTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:account-queries;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AccountQueriesTest {

    private static final LocalDateTime FLAGGED_AT = LocalDateTime.of(2026, 11, 1, 9, 0);

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private LinkReviewRepository linkReviewRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void countsOfOneAccountsLinks() {
        final var owner = user("account@example.com");
        final var other = user("other@example.com");
        link("a", owner, CreatedVia.WEB, null);
        link("b", owner, CreatedVia.WEB, null);
        link("c", owner, CreatedVia.API_KEY, null);
        link("d", owner, null, null);
        link("e", owner, null, null);
        link("blocked", owner, null, BlockReason.ADMIN);
        link("x", other, CreatedVia.WEB, null);
        link("y", other, null, BlockReason.ADMIN);
        entityManager.flush();
        entityManager.createQuery("update Url u set u.active = false where u.shortUrl in ('d', 'e')").executeUpdate();
        entityManager.clear();

        assertThat(urlRepository.countByOwner(owner)).isEqualTo(6);
        assertThat(urlRepository.countByOwnerAndActiveTrueAndBlockReasonIsNull(owner)).isEqualTo(3);
        assertThat(urlRepository.countByOwnerAndActiveFalseAndBlockReasonIsNull(owner)).isEqualTo(2);
        assertThat(urlRepository.countByOwnerAndBlockReasonIsNotNull(owner)).isEqualTo(1);
        assertThat(urlRepository.countLinksByCreatedVia(owner))
                .extracting(CreatedViaCount::getVia, CreatedViaCount::getLinks)
                .containsExactlyInAnyOrder(Tuple.tuple(CreatedVia.WEB, 2L), Tuple.tuple(CreatedVia.API_KEY, 1L));
    }

    @Test
    void theLastTenReviewsOfAnAccountNewestFirst() {
        final var owner = user("account@example.com");
        final var other = user("other@example.com");
        final var domain = CustomDomain.builder().hostname("go.example.com").hostnameKey("go.example.com").status(CustomDomainStatus.ACTIVE)
                .reason("Menu links").createdAt(FLAGGED_AT).updatedAt(FLAGGED_AT).build();
        entityManager.persist(domain);
        final var plain = link("plain", owner, null, null);
        final var onDomain = Url.builder().shortUrl("menu").longUrl("https://menu.example/").owner(owner).domain(domain).build();
        entityManager.persist(onDomain);
        final var foreign = link("foreign", other, null, null);
        IntStream.range(0, 12).forEach(i -> review(i % 2 == 0 ? plain : onDomain, FLAGGED_AT.plusHours(i / 2)));
        review(foreign, FLAGGED_AT.plusDays(30));
        entityManager.flush();
        entityManager.clear();

        final var reviews = linkReviewRepository.findTop10ByUrlOwnerOrderByFlaggedAtDescIdDesc(owner);

        assertThat(reviews).hasSize(10);
        assertThat(reviews).extracting(LinkReview::getFlaggedAt).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(reviews.getFirst().getFlaggedAt()).isEqualTo(FLAGGED_AT.plusHours(5));
        assertThat(reviews.get(0).getId()).isGreaterThan(reviews.get(1).getId());
        assertThat(reviews).allSatisfy(review -> {
            assertThat(Hibernate.isInitialized(review.getUrl())).isTrue();
            assertThat(Hibernate.isInitialized(review.getUrl().getDomain())).isTrue();
        });
        assertThat(reviews).extracting(review -> review.getUrl().getOwner().getId()).containsOnly(owner.getId());
    }

    private User user(final String email) {
        final var user = User.builder().email(email).password("password123").apiKeySlots(1L).build();
        entityManager.persist(user);
        return user;
    }

    private Url link(final String shortUrl, final User owner, final CreatedVia createdVia, final BlockReason blockReason) {
        final var url = Url.builder().shortUrl(shortUrl).longUrl("https://" + shortUrl + ".example/").owner(owner).createdVia(createdVia)
                .blockReason(blockReason).build();
        entityManager.persist(url);
        return url;
    }

    private void review(final Url url, final LocalDateTime flaggedAt) {
        entityManager.persist(LinkReview.builder().url(url).reasons("reason").flaggedAt(flaggedAt).build());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UrlRepository.class, LinkReviewRepository.class}))
    static class TestApplication {
    }

}
