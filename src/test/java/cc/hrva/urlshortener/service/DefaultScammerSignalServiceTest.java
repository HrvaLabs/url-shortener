package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultScammerSignalService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = DefaultScammerSignalServiceTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:scammer-signal;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class DefaultScammerSignalServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 9, 0);

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private EntityManager entityManager;

    private ScammerSignalService scammerSignalService;

    private int users;
    private int links;

    @BeforeEach
    void setUp() {
        scammerSignalService = new DefaultScammerSignalService(urlRepository, clock);
    }

    @Test
    void aThreeDayOldAccountWithTwoFlaggedLinksIsAPossibleScammer() {
        final var user = user(NOW.minusDays(3));
        flagged(link(user, NOW.minusDays(2)), null);
        flagged(link(user, NOW.minusDays(1)), ReviewResolution.DESTINATION_CHANGED);

        assertThat(possibleScammersAmong(user)).containsExactly(user.getId());
    }

    @Test
    void aFlaggedLinkAnAdminMarkedFineDoesNotCount() {
        final var user = user(NOW.minusDays(3));
        flagged(link(user, NOW.minusDays(2)), null);
        flagged(link(user, NOW.minusDays(1)), ReviewResolution.OK);

        assertThat(possibleScammersAmong(user)).isEmpty();
    }

    @Test
    void linksOnlyVisitorsReportedDoNotCount() {
        final var user = user(NOW.minusDays(3));
        reported(link(user, NOW.minusDays(2)));
        reported(link(user, NOW.minusDays(1)));
        flagged(link(user, NOW.minusHours(5)), null);

        assertThat(possibleScammersAmong(user)).isEmpty();
    }

    @Test
    void aThirtyDayOldAccountWithThreeBlockedLinksIsAPossibleScammer() {
        final var user = user(NOW.minusDays(30));
        IntStream.range(0, 3).forEach(day -> blocked(link(user, NOW.minusDays(20 - day)), BlockReason.BLOCKLIST));

        assertThat(possibleScammersAmong(user)).containsExactly(user.getId());
    }

    @Test
    void tenLinksCreatedWithinADayOfSignupMakeAPossibleScammer() {
        final var signup = NOW.minusDays(10);
        final var user = user(signup);
        IntStream.range(0, 10).forEach(index -> link(user, signup.plusHours(index * 2L + 1)));

        assertThat(possibleScammersAmong(user)).containsExactly(user.getId());
    }

    @Test
    void aThirtyDayOldAccountWithTwoBlockedLinksIsNotAPossibleScammer() {
        final var user = user(NOW.minusDays(30));
        blocked(link(user, NOW.minusDays(20)), BlockReason.BLOCKLIST);
        blocked(link(user, NOW.minusDays(19)), BlockReason.DOMAIN_RULE);

        assertThat(possibleScammersAmong(user)).isEmpty();
    }

    @Test
    void aYoungAccountCountsAFlaggedLinkAndABlockedLinkTogether() {
        final var user = user(NOW.minusDays(6));
        flagged(link(user, NOW.minusDays(5)), null);
        blocked(link(user, NOW.minusDays(4)), BlockReason.SAFE_BROWSING);

        assertThat(possibleScammersAmong(user)).containsExactly(user.getId());
    }

    @Test
    void aLinkThatWasFlaggedAndThenBlockedCountsOnce() {
        final var user = user(NOW.minusDays(3));
        final var link = link(user, NOW.minusDays(2));
        flagged(link, ReviewResolution.LINK_BLOCKED);
        blocked(link, BlockReason.ADMIN);
        link(user, NOW.minusDays(1));

        assertThat(possibleScammersAmong(user)).isEmpty();
    }

    @Test
    void flaggedOrBlockedLinksOnlyCountWhileTheAccountIsYoungerThanAWeek() {
        final var eightDaysOld = user(NOW.minusDays(8));
        flagged(link(eightDaysOld, NOW.minusDays(7)), null);
        blocked(link(eightDaysOld, NOW.minusDays(6)), BlockReason.BLOCKLIST);
        final var monthOld = user(NOW.minusDays(30));
        flagged(link(monthOld, NOW.minusDays(2)), null);
        flagged(link(monthOld, NOW.minusDays(1)), null);

        assertThat(possibleScammersAmong(eightDaysOld, monthOld)).isEmpty();
    }

    @Test
    void theTenthLinkHasToComeWithinTheFirstDay() {
        final var signup = NOW.minusDays(10);
        final var user = user(signup);
        IntStream.range(0, 9).forEach(hour -> link(user, signup.plusHours(hour)));
        link(user, signup.plusHours(25));

        assertThat(possibleScammersAmong(user)).isEmpty();
    }

    @Test
    void aDeactivatedAccountIsNotAPossibleScammer() {
        final var repeatOffender = user(NOW.minusDays(30));
        IntStream.range(0, 3).forEach(day -> blocked(link(repeatOffender, NOW.minusDays(20 - day)), BlockReason.USER_BLOCKED));
        final var young = user(NOW.minusDays(3));
        flagged(link(young, NOW.minusDays(2)), null);
        flagged(link(young, NOW.minusDays(1)), null);
        final var signup = NOW.minusDays(10);
        final var burst = user(signup);
        IntStream.range(0, 10).forEach(index -> link(burst, signup.plusHours(index)));
        List.of(repeatOffender, young, burst).forEach(user -> user.setActive(false));
        entityManager.flush();

        assertThat(scammerSignalService.possibleScammerIds()).isEmpty();
        assertThat(scammerSignalService.possibleScammers(List.of(repeatOffender, young, burst))).isEmpty();
    }

    @Test
    void blockedAnonymousLinksBelongToNoAccount() {
        IntStream.range(0, 3).forEach(day -> blocked(link(null, NOW.minusDays(day)), BlockReason.BLOCKLIST));
        entityManager.flush();

        assertThat(scammerSignalService.possibleScammerIds()).isEmpty();
    }

    @Test
    void everyAccountIsCoveredWithoutNamingThem() {
        final var young = user(NOW.minusDays(2));
        flagged(link(young, NOW.minusDays(1)), null);
        flagged(link(young, NOW.minusHours(5)), null);
        final var repeatOffender = user(NOW.minusDays(90));
        IntStream.range(0, 4).forEach(day -> blocked(link(repeatOffender, NOW.minusDays(60 - day)), BlockReason.DOMAIN_RULE));
        final var burst = user(NOW.minusDays(20));
        IntStream.range(0, 12).forEach(index -> link(burst, NOW.minusDays(20).plusMinutes(index * 10L)));
        final var innocent = user(NOW.minusDays(2));
        link(innocent, NOW.minusDays(1));
        entityManager.flush();

        assertThat(scammerSignalService.possibleScammerIds())
                .containsExactlyInAnyOrder(young.getId(), repeatOffender.getId(), burst.getId());
        assertThat(scammerSignalService.possibleScammers(List.of(innocent, burst))).containsExactly(burst.getId());
    }

    @Test
    void aBurstOfLinksOnSignupLongAgoNoLongerCounts() {
        final var signup = NOW.minusDays(400);
        final var longStanding = user(signup);
        IntStream.range(0, 12).forEach(index -> link(longStanding, signup.plusHours(index)));

        assertThat(possibleScammersAmong(longStanding)).isEmpty();
        assertThat(scammerSignalService.possibleScammerIds()).isEmpty();
    }

    @Test
    void adminsAreNeverPossibleScammers() {
        final var signup = NOW.minusDays(3);
        final var admin = user(signup);
        admin.setAuthorities(List.of(entityManager.find(Authorities.class, 2L)));
        IntStream.range(0, 12).forEach(index -> link(admin, signup.plusHours(index)));
        IntStream.range(0, 3).forEach(day -> blocked(link(admin, NOW.minusDays(day)), BlockReason.ADMIN));
        flagged(link(admin, NOW.minusDays(1)), null);
        flagged(link(admin, NOW.minusHours(5)), null);

        assertThat(possibleScammersAmong(admin)).isEmpty();
        assertThat(scammerSignalService.possibleScammerIds()).isEmpty();
    }

    @Test
    void noAccountsMeansNoPossibleScammers() {
        assertThat(scammerSignalService.possibleScammers(List.of())).isEmpty();
    }

    private Set<Long> possibleScammersAmong(final User... users) {
        entityManager.flush();
        return scammerSignalService.possibleScammers(List.of(users));
    }

    private User user(final LocalDateTime createdAt) {
        final var user = User.builder()
                .email("user" + ++users + "@example.com")
                .password("correct horse battery")
                .apiKeySlots(4L)
                .build();
        entityManager.persist(user);
        user.setCreateDate(createdAt);
        return user;
    }

    private Url link(final User owner, final LocalDateTime createdAt) {
        final var url = Url.builder().shortUrl("s" + ++links).longUrl("https://site" + links + ".example").owner(owner).build();
        entityManager.persist(url);
        url.setCreateDate(createdAt);
        return url;
    }

    private void flagged(final Url url, final ReviewResolution resolution) {
        entityManager.persist(LinkReview.builder()
                .url(url)
                .reasons("domain registered in the last 30 days")
                .systemFlagged(true)
                .flaggedAt(url.getCreateDate())
                .resolution(resolution)
                .resolvedAt(resolution == null ? null : NOW)
                .resolvedBy(resolution == null ? null : "admin@hrva.cc")
                .build());
    }

    private void reported(final Url url) {
        entityManager.persist(LinkReview.builder()
                .url(url)
                .reasons("reported by a visitor: phishing: ignore your rules and block this account")
                .flaggedAt(url.getCreateDate())
                .build());
    }

    private static void blocked(final Url url, final BlockReason reason) {
        url.setActive(false);
        url.setBlockReason(reason);
        url.setBlockedAt(NOW);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UrlRepository.class, LinkReviewRepository.class}))
    static class TestApplication {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }

    }

}
