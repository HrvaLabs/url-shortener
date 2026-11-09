package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.dto.LinkDestination;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
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
        classes = UrlRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:url-repository;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class UrlRepositoryTest {

    private static final LocalDateTime BLOCKED_AT = LocalDateTime.of(2026, 10, 22, 9, 0);
    private static final LocalDateTime VISITED_AT = LocalDateTime.of(2026, 10, 24, 18, 30);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 20, 12, 0);

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void aliasTakenIgnoringCaseMatchesAnyCase() {
        link("Karlo-GitHub", null);
        final var domain = CustomDomain.builder().hostname("go.example.com").hostnameKey("go.example.com").status(CustomDomainStatus.ACTIVE)
                .reason("Menu links for my cafe").createdAt(CREATED_AT).updatedAt(CREATED_AT).build();
        entityManager.persist(domain);
        final var onDomain = Url.builder().shortUrl("Menu").longUrl("https://menu.example/").domain(domain).build();
        entityManager.persist(onDomain);
        entityManager.flush();

        assertThat(urlRepository.aliasTakenIgnoringCase(null, "karlo-github")).isTrue();
        assertThat(urlRepository.aliasTakenIgnoringCase(null, "KARLO-GITHUB")).isTrue();
        assertThat(urlRepository.aliasTakenIgnoringCase(null, "menu")).isFalse();
        assertThat(urlRepository.aliasTakenIgnoringCase(null, "free")).isFalse();
        assertThat(urlRepository.aliasTakenIgnoringCase(domain.getId(), "MENU")).isTrue();
        assertThat(urlRepository.aliasTakenIgnoringCase(domain.getId(), "karlo-github")).isFalse();
    }

    @Test
    void onlyActiveLinksPastTheirExpiryAreDueToBeSwitchedOff() {
        final var expired = link("old", LocalDateTime.now().minusMinutes(5));
        link("fresh", LocalDateTime.now().plusDays(1));
        link("forever", null);
        final var alreadyOff = link("off", LocalDateTime.now().minusMinutes(5));
        blockMeanwhile(alreadyOff);

        assertThat(urlRepository.findExpiredActiveIds(LocalDateTime.now())).containsExactly(expired.getId());
    }

    @Test
    void switchingOffAnExpiredLinkLeavesABlockThatLandedMeanwhileAlone() {
        final var expired = link("old", LocalDateTime.now().minusMinutes(5));
        final var ids = urlRepository.findExpiredActiveIds(LocalDateTime.now());
        blockMeanwhile(expired);

        final var switchedOff = urlRepository.deactivateIfActive(ids);

        entityManager.clear();
        assertThat(switchedOff).isZero();
        assertThat(urlRepository.findById(expired.getId()).orElseThrow())
                .extracting(Url::isActive, Url::getBlockReason, Url::getBlockDetail, Url::getBlockedAt)
                .containsExactly(false, BlockReason.BLOCKLIST, "URLhaus", BLOCKED_AT);
    }

    @Test
    void onlyTheGivenLinksAreSwitchedOff() {
        final var expired = link("old", LocalDateTime.now().minusMinutes(5));
        final var other = link("other", LocalDateTime.now().minusMinutes(5));

        final var switchedOff = urlRepository.deactivateIfActive(List.of(expired.getId()));

        entityManager.clear();
        assertThat(switchedOff).isEqualTo(1);
        assertThat(urlRepository.findById(expired.getId()).orElseThrow())
                .extracting(Url::isActive, Url::getLongUrl, Url::getBlockReason, Url::getBlockedAt)
                .containsExactly(false, "https://old.example/", null, null);
        assertThat(urlRepository.findById(other.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void aVisitToALinkSwitchedOffMeanwhileChangesNothing() {
        final var switchedOff = link("off", null);
        urlRepository.deactivateIfActive(List.of(switchedOff.getId()));

        final var recorded = urlRepository.recordVisit(switchedOff.getId(), VISITED_AT);

        entityManager.clear();
        assertThat(recorded).isZero();
        assertThat(urlRepository.findById(switchedOff.getId()).orElseThrow())
                .extracting(Url::getVisits, Url::getLastAccessed)
                .containsExactly(0L, null);
    }

    @Test
    void eachOpenOfALimitedLinkIsCountedUpToTheLimitAndTheLastOneSwitchesItOff() {
        final var limited = limitedLink("limited", 3L);

        final var opens = IntStream.rangeClosed(1, 5).map(attempt -> urlRepository.consumeOpen(limited.getId())).boxed().toList();

        entityManager.clear();
        assertThat(opens).containsExactly(1, 1, 1, 0, 0);
        assertThat(urlRepository.findById(limited.getId()).orElseThrow())
                .extracting(Url::getOpens, Url::isActive, Url::getVisits)
                .containsExactly(3L, false, 0L);
    }

    @Test
    void anOpenBelowTheLimitKeepsTheLinkOn() {
        final var limited = limitedLink("roomy", 3L);

        urlRepository.consumeOpen(limited.getId());

        entityManager.clear();
        assertThat(urlRepository.findById(limited.getId()).orElseThrow())
                .extracting(Url::getOpens, Url::isActive)
                .containsExactly(1L, true);
    }

    @Test
    void aLinkWithoutALimitOrSwitchedOffUsesNoOpen() {
        final var unlimited = link("unlimited", null);
        final var switchedOff = limitedLink("switched-off", 3L);
        urlRepository.deactivateIfActive(List.of(switchedOff.getId()));

        final var used = urlRepository.consumeOpen(unlimited.getId()) + urlRepository.consumeOpen(switchedOff.getId());

        entityManager.clear();
        assertThat(used).isZero();
        assertThat(urlRepository.findAllById(List.of(unlimited.getId(), switchedOff.getId())))
                .extracting(Url::getOpens)
                .containsOnly(0L);
    }

    @Test
    void theVisitOfTheLastOpenIsStillCountedAfterTheLimitSwitchedTheLinkOff() {
        final var limited = limitedLink("last-open", 1L);
        urlRepository.consumeOpen(limited.getId());

        final var recorded = urlRepository.recordVisit(limited.getId(), VISITED_AT);

        entityManager.clear();
        assertThat(recorded).isEqualTo(1);
        assertThat(urlRepository.findById(limited.getId()).orElseThrow())
                .extracting(Url::getVisits, Url::getLastAccessed, Url::isActive)
                .containsExactly(1L, VISITED_AT, false);
    }

    @Test
    void aVisitToAnActiveLinkCountsItAndStampsTheTime() {
        final var active = link("live", null);

        final var recorded = urlRepository.recordVisit(active.getId(), VISITED_AT);

        entityManager.clear();
        assertThat(recorded).isEqualTo(1);
        assertThat(urlRepository.findById(active.getId()).orElseThrow())
                .extracting(Url::getVisits, Url::getLastAccessed)
                .containsExactly(1L, VISITED_AT);
    }

    @Test
    void recentLinksAreTheNewestTenWithTheirOwner() {
        final var owner = savedOwner("recent-owner@example.com");
        final var links = IntStream.rangeClosed(1, 12).mapToObj(index -> link("recent-" + index, null)).toList();
        links.getFirst().setOwner(owner);
        entityManager.flush();
        createdAt(links.getFirst(), CREATED_AT.plusHours(1));
        IntStream.rangeClosed(1, 10).forEach(index -> createdAt(links.get(index), CREATED_AT.plusMinutes(index)));
        createdAt(links.get(11), CREATED_AT.plusMinutes(10));

        entityManager.clear();
        final var recent = urlRepository.findTop10ByOrderByCreateDateDescIdDesc();

        assertThat(links.get(10).getId()).isLessThan(links.get(11).getId());
        assertThat(recent).hasSize(10);
        assertThat(recent).extracting(Url::getId).containsExactly(
                links.get(0).getId(),
                links.get(11).getId(),
                links.get(10).getId(),
                links.get(9).getId(),
                links.get(8).getId(),
                links.get(7).getId(),
                links.get(6).getId(),
                links.get(5).getId(),
                links.get(4).getId(),
                links.get(3).getId());
        assertThat(Hibernate.isInitialized(recent.getFirst().getOwner())).isTrue();
        assertThat(recent.getFirst().getOwner().getEmail()).isEqualTo("recent-owner@example.com");
        assertThat(recent.subList(1, 10)).extracting(Url::getOwner).containsOnlyNulls();
    }

    @Test
    void theDestinationsOfTheGivenLinksAreReadWhateverTheirStateAndUnknownIdsAreLeftOut() {
        final var active = link("active", null);
        final var blocked = link("blocked", null);
        blockMeanwhile(blocked);
        link("other", null);

        final var destinations = urlRepository.findDestinationsByIdIn(List.of(blocked.getId(), active.getId(), Long.MAX_VALUE));

        assertThat(destinations).containsExactlyInAnyOrder(
                new LinkDestination(active.getId(), "https://active.example/"),
                new LinkDestination(blocked.getId(), "https://blocked.example/"));
    }

    @Test
    void deduplicationCandidatesAreTheActiveLinksWithoutAnOwnerForThatDestination() {
        final var owner = savedOwner("dedup-owner@example.com");
        final var first = linkTo("dedup-a", "https://shared.example/page");
        final var second = linkTo("dedup-b", "https://shared.example/page");
        final var owned = linkTo("dedup-owned", "https://shared.example/page");
        owned.setOwner(owner);
        final var blocked = linkTo("dedup-blocked", "https://shared.example/page");
        blockMeanwhile(blocked);
        linkTo("dedup-other", "https://shared.example/other");
        entityManager.flush();
        entityManager.clear();

        final var candidates = urlRepository.findByLongUrlAndActiveTrueAndOwnerIsNull("https://shared.example/page");

        assertThat(candidates).extracting(Url::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(urlRepository.findByLongUrlAndActiveTrueAndOwnerIsNull("https://nobody.example/")).isEmpty();
    }

    private Url linkTo(final String shortUrl, final String longUrl) {
        final var url = Url.builder().shortUrl(shortUrl).longUrl(longUrl).build();
        entityManager.persist(url);
        entityManager.flush();
        return url;
    }

    private Url link(final String shortUrl, final LocalDateTime expirationDate) {
        final var url = Url.builder().shortUrl(shortUrl).longUrl("https://" + shortUrl + ".example/").expirationDate(expirationDate).build();
        entityManager.persist(url);
        entityManager.flush();
        return url;
    }

    private Url limitedLink(final String shortUrl, final long visitLimit) {
        final var url = Url.builder().shortUrl(shortUrl).longUrl("https://" + shortUrl + ".example/").visitLimit(visitLimit).build();
        entityManager.persist(url);
        entityManager.flush();
        return url;
    }

    private User savedOwner(final String email) {
        final var owner = User.builder().email(email).password("correct horse battery").build();
        entityManager.persist(owner);
        return owner;
    }

    private void createdAt(final Url url, final LocalDateTime createDate) {
        entityManager.createQuery("update Url u set u.createDate = :createDate where u.id = :id")
                .setParameter("createDate", createDate)
                .setParameter("id", url.getId())
                .executeUpdate();
    }

    private void blockMeanwhile(final Url url) {
        entityManager.createQuery("update Url u set u.active = false, u.blockReason = :reason, u.blockDetail = :detail, u.blockedAt = :blockedAt "
                        + "where u.id = :id")
                .setParameter("reason", BlockReason.BLOCKLIST)
                .setParameter("detail", "URLhaus")
                .setParameter("blockedAt", BLOCKED_AT)
                .setParameter("id", url.getId())
                .executeUpdate();
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
