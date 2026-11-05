package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.model.enums.SourceKind;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = AccountSignInCheckSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:account-sign-in-check-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AccountSignInCheckSchemaTest {

    @Autowired
    private BlocklistSourceRepository blocklistSourceRepository;

    @Autowired
    private LinkReviewRepository linkReviewRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void the052ChangesetsRunAfter051() {
        assertThat(jdbcTemplate.queryForList("select id from databasechangelog where id like '051-%' or id like '052-%' order by orderexecuted",
                String.class))
                .startsWith("051-virustotal-submitted-id", "052-blocklist-source-tranco", "052-link-review-notice", "052-link-review-checked-url");
    }

    @Test
    void seedsTheTrancoTopSitesAsAListOfRealSignInDomains() {
        assertThat(blocklistSourceRepository.findAll())
                .filteredOn(source -> source.getKind() == SourceKind.TOP_SITES)
                .extracting(BlocklistSource::getName, BlocklistSource::getUrl, BlocklistSource::getFallbackUrls, BlocklistSource::getAction,
                        BlocklistSource::isEnabled, BlocklistSource::getRefreshIntervalHours, BlocklistSource::getDomainCount,
                        BlocklistSource::getLastSuccessAt)
                .containsExactly(tuple("Tranco top sites", "https://tranco-list.eu/api/lists/date/latest?subdomains=true", List.of(), BlocklistAction.TRUST,
                        true, 24, null, null));
        assertThat(blocklistSourceRepository.findAll())
                .filteredOn(source -> source.getAction() == BlocklistAction.TRUST)
                .extracting(BlocklistSource::getName)
                .containsExactly("Tranco top sites", "Tranco brand sites");
    }

    @Test
    void aReviewSavedWithoutTheNewColumnsIsNoNoticeAndHasNoCheckedUrl() {
        final var url = savedLink("older", "https://a.example/", null);
        jdbcTemplate.update("insert into link_review (url_id, reasons, flagged_at, system_flagged) values (?, 'imitates jofogas', current_timestamp, true)",
                url.getId());

        assertThat(jdbcTemplate.queryForMap("select notice, checked_url from link_review where url_id = ?", url.getId()))
                .containsEntry("NOTICE", false)
                .containsEntry("CHECKED_URL", null);
    }

    @Test
    void aLongCheckedUrlIsStoredInFull() {
        final var destination = StringUtils.rightPad("https://a.example/", 2048, 'a');
        final var url = savedLink("long", destination, null);
        final var review = linkReviewRepository.saveAndFlush(LinkReview.builder().url(url).reasons("Sign-in form on a link from an account")
                .systemFlagged(true).notice(true).checkedUrl(destination).build());
        entityManager.clear();

        assertThat(linkReviewRepository.findById(review.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.isNotice()).isTrue();
            assertThat(stored.getCheckedUrl()).isEqualTo(destination);
        });
    }

    @Test
    void anOpenNoticeDoesNotCountAsBeingChecked() {
        final var noticeOwner = savedUser("older@example.com");
        final var flaggedOwner = savedUser("newcomer@example.com");
        final var noticed = savedLink("noticed", "https://a.example/login", noticeOwner);
        final var flagged = savedLink("flagged", "https://b.example/login", flaggedOwner);
        entityManager.persist(openReview(noticed, true));
        entityManager.persist(openReview(flagged, false));
        entityManager.flush();
        entityManager.clear();

        assertThat(linkReviewRepository.existsByUrl_Owner_IdAndResolvedAtIsNullAndSystemFlaggedTrueAndNoticeFalse(noticeOwner.getId())).isFalse();
        assertThat(linkReviewRepository.existsByUrl_Owner_IdAndResolvedAtIsNullAndSystemFlaggedTrueAndNoticeFalse(flaggedOwner.getId())).isTrue();
        assertThat(linkReviewRepository.existsByUrl_IdAndResolvedAtIsNullAndSystemFlaggedTrueAndNoticeFalse(noticed.getId())).isFalse();
        assertThat(linkReviewRepository.existsByUrl_IdAndResolvedAtIsNullAndSystemFlaggedTrueAndNoticeFalse(flagged.getId())).isTrue();
        assertThat(linkReviewRepository.findOwnerIdsWithOpenSystemReviews(List.of(noticeOwner.getId(), flaggedOwner.getId())))
                .containsExactly(flaggedOwner.getId());
        assertThat(linkReviewRepository.findOpenSystemFlaggedUrlIds(List.of(noticed.getId(), flagged.getId()))).containsExactly(flagged.getId());
    }

    @Test
    void anAdminCanStillActOnANoticedLinkThroughMcp() {
        final var owner = savedUser("older@example.com");
        final var noticed = savedLink("noticed", "https://a.example/login", owner);
        entityManager.persist(openReview(noticed, true));
        entityManager.flush();
        entityManager.clear();

        assertThat(linkReviewRepository.existsByUrlAndResolvedAtIsNullAndSystemFlaggedTrue(noticed)).isTrue();
        assertThat(linkReviewRepository.existsByUrlOwnerAndResolvedAtIsNullAndSystemFlaggedTrue(owner)).isTrue();
    }

    @Test
    void aNoticeDoesNotCountAsFlagged() {
        final var noticeOwner = savedUser("older@example.com");
        final var flaggedOwner = savedUser("newcomer@example.com");
        final var blockedOwner = savedUser("blocked@example.com");
        entityManager.persist(openReview(savedLink("noticed", "https://a.example/login", noticeOwner), true));
        entityManager.persist(openReview(savedLink("flagged", "https://b.example/login", flaggedOwner), false));
        final var blocked = savedLink("blocked", "https://c.example/login", blockedOwner);
        blocked.setActive(false);
        blocked.setBlockReason(BlockReason.ADMIN);
        final var blockedNotice = openReview(blocked, true);
        blockedNotice.setResolution(ReviewResolution.LINK_BLOCKED);
        blockedNotice.setResolvedAt(LocalDateTime.now());
        entityManager.persist(blockedNotice);
        entityManager.flush();
        entityManager.clear();

        assertThat(urlRepository.countFlaggedByOwner(noticeOwner)).isZero();
        assertThat(urlRepository.countFlaggedByOwner(flaggedOwner)).isEqualTo(1);
        assertThat(urlRepository.findOwnerIdsSignedUpAfterWithFlaggedOrBlockedLinks(LocalDateTime.now().minusDays(30), 1))
                .containsExactlyInAnyOrder(flaggedOwner.getId(), blockedOwner.getId());
    }

    private static LinkReview openReview(final Url url, final boolean notice) {
        return LinkReview.builder().url(url).reasons("Sign-in form on a link from an account").systemFlagged(true).notice(notice)
                .checkedUrl(url.getLongUrl()).build();
    }

    private Url savedLink(final String shortUrl, final String longUrl, final User owner) {
        return urlRepository.saveAndFlush(Url.builder().shortUrl(shortUrl).longUrl(longUrl).owner(owner).build());
    }

    private User savedUser(final String email) {
        final var user = User.builder().email(email).password("correct horse battery").apiKeySlots(4L).build();
        entityManager.persist(user);
        return user;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {BlocklistSourceRepository.class, LinkReviewRepository.class, UrlRepository.class}))
    static class TestApplication {
    }

}
