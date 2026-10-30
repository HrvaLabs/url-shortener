package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.BioHandleHold;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.BioPageReview;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioPageReviewResolution;
import cc.hrva.urlshortener.model.enums.BioTheme;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.assertj.core.api.ThrowingConsumer;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import cc.hrva.urlshortener.repository.specification.BioPageReviewSpecification;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = BioPageSchemaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:bio-schema;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class BioPageSchemaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 26);
    private static final String FAMILY_EMOJI = codePoints(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467, 0x200D, 0x1F466);
    private static final String INSERT_VIEWS = "insert into bio_page_daily_views (page_id, stat_date) values (?, ?)";

    @Autowired
    private BioPageRepository bioPageRepository;

    @Autowired
    private BioPageButtonRepository bioPageButtonRepository;

    @Autowired
    private BioHandleHoldRepository bioHandleHoldRepository;

    @Autowired
    private BioPageReviewRepository bioPageReviewRepository;

    @Autowired
    private LinkReviewRepository linkReviewRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void everyChangesetOf029Ran() {
        assertThat(jdbcTemplate.queryForList("select id, exectype from databasechangelog where id like '029-%' order by orderexecuted"))
                .extracting(row -> row.get("ID"), row -> row.get("EXECTYPE"))
                .containsExactly(
                        tuple("029-bio-page", "EXECUTED"),
                        tuple("029-bio-page-button", "EXECUTED"),
                        tuple("029-bio-handle-hold", "EXECUTED"),
                        tuple("029-bio-page-daily-views", "EXECUTED"),
                        tuple("029-bio-page-review", "EXECUTED"));
    }

    @Test
    void keysAndIndexesCarryTheirNames() {
        assertThat(jdbcTemplate.queryForList(
                "select upper(constraint_name) from information_schema.table_constraints where upper(table_name) like 'BIO\\_%'", String.class))
                .contains(
                        "UQ_BIO_PAGE_USER_ID",
                        "UQ_BIO_PAGE_HANDLE",
                        "FK_BIO_PAGE_USER",
                        "UQ_BIO_PAGE_BUTTON_PAGE_URL",
                        "FK_BIO_PAGE_BUTTON_PAGE",
                        "FK_BIO_PAGE_BUTTON_URL",
                        "PK_BIO_HANDLE_HOLD",
                        "FK_BIO_HANDLE_HOLD_PAGE",
                        "PK_BIO_PAGE_DAILY_VIEWS",
                        "FK_BIO_PAGE_DAILY_VIEWS_PAGE",
                        "FK_BIO_PAGE_REVIEW_PAGE");
        assertThat(jdbcTemplate.queryForList("select upper(index_name) from information_schema.indexes", String.class))
                .contains(
                        "IDX_BIO_PAGE_BUTTON_URL_ID",
                        "IDX_BIO_HANDLE_HOLD_HELD_UNTIL",
                        "IDX_BIO_PAGE_DAILY_VIEWS_STAT_DATE",
                        "IDX_BIO_PAGE_REVIEW_PAGE_ID",
                        "IDX_BIO_PAGE_REVIEW_RESOLVED_AT");
    }

    @Test
    void onePagePerAccount() {
        final var ana = savedUser("ana@example.com");
        bioPageRepository.saveAndFlush(page(ana, "ana").build());
        final var secondPage = page(ana, "ana-two").build();

        assertThatThrownBy(() -> bioPageRepository.saveAndFlush(secondPage))
                .satisfies(violates("uq_bio_page_user_id"));
    }

    @Test
    void handlesAreUniqueButMayBeNull() {
        bioPageRepository.saveAndFlush(page(savedUser("first@example.com"), null).build());
        bioPageRepository.saveAndFlush(page(savedUser("second@example.com"), null).build());
        assertThat(jdbcTemplate.queryForObject("select count(*) from bio_page where handle is null", Long.class)).isEqualTo(2L);

        bioPageRepository.saveAndFlush(page(savedUser("ana@example.com"), "ana").build());
        final var sameHandle = page(savedUser("other-ana@example.com"), "ana").build();

        assertThatThrownBy(() -> bioPageRepository.saveAndFlush(sameHandle))
                .satisfies(violates("uq_bio_page_handle"));
    }

    @Test
    void aPageRoundTripsWithDefaults() {
        final var saved = bioPageRepository.saveAndFlush(BioPage.builder().owner(savedUser("ana@example.com")).handle("ana").build());
        entityManager.clear();

        assertThat(bioPageRepository.findById(saved.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.getHandle()).isEqualTo("ana");
            assertThat(stored.getDisplayName()).isEmpty();
            assertThat(stored.getBio()).isEmpty();
            assertThat(stored.getAvatarKind()).isEqualTo(BioAvatarKind.INITIALS);
            assertThat(stored.getAvatarEmoji()).isNull();
            assertThat(stored.getAccent()).isEqualTo(BioAccent.BLUE);
            assertThat(stored.getTheme()).isEqualTo(BioTheme.AUTO);
            assertThat(stored.isIndexable()).isFalse();
            assertThat(stored.getHiddenAt()).isNull();
            assertThat(stored.getCreatedAt()).isNotNull();
            assertThat(stored.getUpdatedAt()).isEqualTo(stored.getCreatedAt());
            assertThat(stored.isPublic()).isTrue();
        });
    }

    @Test
    void plainInsertsGetTheColumnDefaults() {
        final var ana = savedUser("ana@example.com");
        jdbcTemplate.update("insert into bio_page (user_id, avatar_kind, accent, theme, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
                ana.getId(), "INITIALS", "BLUE", "AUTO", NOW, NOW);

        assertThat(jdbcTemplate.queryForMap("select handle, display_name, bio, indexable from bio_page where user_id = ?", ana.getId()))
                .containsEntry("HANDLE", null)
                .containsEntry("DISPLAY_NAME", "")
                .containsEntry("BIO", "")
                .containsEntry("INDEXABLE", false);
    }

    @Test
    void emojiSequencesFit() {
        assertThat(FAMILY_EMOJI).hasSize(11);
        final var saved = bioPageRepository.saveAndFlush(page(savedUser("ana@example.com"), "ana")
                .avatarKind(BioAvatarKind.EMOJI)
                .avatarEmoji(FAMILY_EMOJI)
                .build());
        entityManager.clear();

        assertThat(bioPageRepository.findById(saved.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.getAvatarKind()).isEqualTo(BioAvatarKind.EMOJI);
            assertThat(stored.getAvatarEmoji()).isEqualTo(FAMILY_EMOJI);
        });
    }

    @Test
    void aLinkCanBeOnAPageOnlyOnce() {
        final var ana = savedUser("ana@example.com");
        final var page = savedPage(ana, "ana");
        final var link = savedLink("abc", ana);
        bioPageButtonRepository.saveAndFlush(button(page, link, 0, true));
        final var sameLinkAgain = button(page, link, 1, true);

        assertThatThrownBy(() -> bioPageButtonRepository.saveAndFlush(sameLinkAgain))
                .satisfies(violates("uq_bio_page_button_page_url"));
    }

    @Test
    void aButtonDefaultsToVisible() {
        final var ana = savedUser("ana@example.com");
        final var page = savedPage(ana, "ana");
        final var link = savedLink("abc", ana);
        final var otherLink = savedLink("blog", ana);
        jdbcTemplate.update("insert into bio_page_button (page_id, url_id, title, sort_order) values (?, ?, ?, ?)",
                page.getId(), link.getId(), "Shop", 0);
        bioPageButtonRepository.saveAndFlush(BioPageButton.builder().page(page).url(otherLink).title("Blog").sortOrder(1).build());
        entityManager.clear();

        assertThat(jdbcTemplate.queryForList("select visible from bio_page_button where page_id = ? order by sort_order", Boolean.class, page.getId()))
                .containsExactly(true, true);
        assertThat(bioPageButtonRepository.findByPageWithUrl(page))
                .extracting(BioPageButton::getTitle, BioPageButton::isVisible)
                .containsExactly(tuple("Shop", true), tuple("Blog", true));
    }

    @Test
    void theDomainsOfAPagesButtonsCostNoExtraStatement() {
        final var ana = savedUser("ana@example.com");
        final var page = savedPage(ana, "ana");
        final var onDomain = savedLink("menu", ana);
        final var onOtherDomain = savedLink("shop", ana);
        final var onHrva = savedLink("blog", ana);
        bioPageButtonRepository.saveAndFlush(button(page, onDomain, 0, true));
        bioPageButtonRepository.saveAndFlush(button(page, onOtherDomain, 1, true));
        bioPageButtonRepository.saveAndFlush(button(page, onHrva, 2, true));
        final var withoutDomains = statementsToLoadTheButtonsOf(page);
        jdbcTemplate.update("""
                insert into custom_domain (owner_id, hostname, hostname_key, status, reason, created_at, updated_at)
                values (?, 'go.example.com', 'go.example.com', 'ACTIVE', 'Menu links for my cafe', ?, ?),
                       (?, 'shop.example.com', 'shop.example.com', 'ACTIVE', 'Menu links for my cafe', ?, ?)""",
                ana.getId(), NOW, NOW, ana.getId(), NOW, NOW);
        jdbcTemplate.update("update url set domain_id = (select id from custom_domain where hostname_key = 'go.example.com') where id = ?", onDomain.getId());
        jdbcTemplate.update("update url set domain_id = (select id from custom_domain where hostname_key = 'shop.example.com') where id = ?",
                onOtherDomain.getId());

        assertThat(statementsToLoadTheButtonsOf(page)).isEqualTo(withoutDomains);
        assertThat(bioPageButtonRepository.findByPageWithUrl(page))
                .extracting(button -> button.getUrl().getDomain() == null ? null : button.getUrl().getDomain().getHostname())
                .containsExactly("go.example.com", "shop.example.com", null);
    }

    @Test
    void deletingALinkRemovesItsButton() {
        final var ana = savedUser("ana@example.com");
        final var page = savedPage(ana, "ana");
        final var deleted = savedLink("gone", ana);
        final var kept = savedLink("kept", ana);
        bioPageButtonRepository.saveAndFlush(button(page, deleted, 0, true));
        bioPageButtonRepository.saveAndFlush(button(page, kept, 1, true));

        jdbcTemplate.update("delete from url where id = ?", deleted.getId());

        assertThat(jdbcTemplate.queryForList("select url_id from bio_page_button where page_id = ?", Long.class, page.getId()))
                .containsExactly(kept.getId());
    }

    @Test
    void deletingTheAccountDeletesThePageAndEverythingOnIt() {
        final var ana = savedUser("ana@example.com");
        final var bob = savedUser("bob@example.com");
        final var anasPage = savedPage(ana, "ana");
        final var bobsPage = savedPage(bob, "bob");
        final var anonymousLink = savedLink("shared", null);
        for (final var page : List.of(anasPage, bobsPage)) {
            bioPageButtonRepository.saveAndFlush(button(page, anonymousLink, 0, true));
            bioPageReviewRepository.saveAndFlush(BioPageReview.builder().page(page).reasons("name imitates a brand").systemFlagged(true).build());
            jdbcTemplate.update(INSERT_VIEWS, page.getId(), DAY);
        }

        jdbcTemplate.update("delete from user_account where id = ?", ana.getId());

        assertThat(jdbcTemplate.queryForObject("select count(*) from bio_page where user_id = ?", Long.class, ana.getId())).isZero();
        assertThat(rowsOfPage("bio_page_button", anasPage)).isZero();
        assertThat(rowsOfPage("bio_page_review", anasPage)).isZero();
        assertThat(rowsOfPage("bio_page_daily_views", anasPage)).isZero();
        assertThat(rowsOfPage("bio_page_button", bobsPage)).isEqualTo(1L);
        assertThat(rowsOfPage("bio_page_review", bobsPage)).isEqualTo(1L);
        assertThat(rowsOfPage("bio_page_daily_views", bobsPage)).isEqualTo(1L);
    }

    @Test
    void deletingAPageKeepsItsHoldUnlinked() {
        final var page = savedPage(savedUser("ana@example.com"), "ana-new");
        bioHandleHoldRepository.saveAndFlush(hold("ana", page, NOW.plusDays(30)));

        jdbcTemplate.update("delete from bio_page where id = ?", page.getId());

        assertThat(jdbcTemplate.queryForList("select handle, page_id from bio_handle_hold"))
                .extracting(row -> row.get("HANDLE"), row -> row.get("PAGE_ID"))
                .containsExactly(tuple("ana", null));
    }

    @Test
    void oneViewRowPerPageAndDay() {
        final var page = savedPage(savedUser("ana@example.com"), "ana");
        jdbcTemplate.update(INSERT_VIEWS, page.getId(), DAY);
        assertThat(jdbcTemplate.queryForMap("select views, bot_views from bio_page_daily_views where page_id = ?", page.getId()))
                .containsEntry("VIEWS", 0L)
                .containsEntry("BOT_VIEWS", 0L);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_VIEWS, page.getId(), DAY))
                .satisfies(violates("pk_bio_page_daily_views"));
    }

    @Test
    void buttonCountsGroupByPage() {
        final var ana = savedUser("ana@example.com");
        final var bob = savedUser("bob@example.com");
        final var anasPage = savedPage(ana, "ana");
        final var bobsPage = savedPage(bob, "bob");
        bioPageButtonRepository.saveAndFlush(button(anasPage, savedLink("shop", ana), 0, true));
        bioPageButtonRepository.saveAndFlush(button(anasPage, savedLink("draft", ana), 1, false));
        bioPageButtonRepository.saveAndFlush(button(bobsPage, savedLink("blog", bob), 0, true));

        assertThat(bioPageButtonRepository.countByPageIds(List.of(anasPage.getId(), bobsPage.getId())))
                .containsExactlyInAnyOrder(
                        new BioButtonCount(anasPage.getId(), 2L, 1L),
                        new BioButtonCount(bobsPage.getId(), 1L, 1L));
    }

    @Test
    void openReviewsComeNewestFirstWithTheirOwnersAndCountWithoutResolvedOnes() {
        final var anasPage = savedPage(savedUser("ana@example.com"), "ana");
        final var bobsPage = savedPage(savedUser("bob@example.com"), "bob");
        final var cleosPage = savedPage(savedUser("cleo@example.com"), "cleo");
        final var oldest = bioPageReviewRepository.saveAndFlush(bioPageReview(anasPage, NOW.minusHours(2), null));
        final var sameTimeFirst = bioPageReviewRepository.saveAndFlush(bioPageReview(bobsPage, NOW.minusHours(1), null));
        final var sameTimeSecond = bioPageReviewRepository.saveAndFlush(bioPageReview(cleosPage, NOW.minusHours(1), null));
        bioPageReviewRepository.saveAndFlush(bioPageReview(anasPage, NOW, NOW));
        entityManager.clear();

        final var newestFirst = Sort.by(Sort.Order.desc("flaggedAt"), Sort.Order.desc("id"));
        final var firstPage = bioPageReviewRepository.findAll(BioPageReviewSpecification.isOpen(), PageRequest.of(0, 2, newestFirst));
        final var secondPage = bioPageReviewRepository.findAll(BioPageReviewSpecification.isOpen(), PageRequest.of(1, 2, newestFirst));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent())
                .extracting(BioPageReview::getId, review -> review.getPage().getOwner().getEmail())
                .containsExactly(
                        tuple(sameTimeSecond.getId(), "cleo@example.com"),
                        tuple(sameTimeFirst.getId(), "bob@example.com"));
        assertThat(secondPage.getContent()).extracting(BioPageReview::getId).containsExactly(oldest.getId());
        assertThat(bioPageReviewRepository.countByResolvedAtIsNull()).isEqualTo(3);
    }

    @Test
    void newReasonsSavedAfterAnAdminResolvedTheReviewLeaveItResolved() {
        final var page = savedPage(savedUser("ana@example.com"), "ana");
        final var review = bioPageReviewRepository.saveAndFlush(
                BioPageReview.builder().page(page).reasons("visitor report: spam").flaggedAt(NOW.minusHours(1)).systemFlagged(false).build());
        jdbcTemplate.update("update bio_page_review set resolved_at = ?, resolved_by = ?, resolution = ? where id = ?",
                NOW, "admin@hrva.cc", "OK", review.getId());

        review.setReasons(review.getReasons() + "\nname imitates paypal");
        review.setSystemFlagged(true);
        bioPageReviewRepository.saveAndFlush(review);

        assertThat(jdbcTemplate.queryForMap("select resolved_at, resolved_by, resolution, system_flagged from bio_page_review where id = ?",
                review.getId()))
                .containsEntry("RESOLVED_BY", "admin@hrva.cc")
                .containsEntry("RESOLUTION", "OK")
                .containsEntry("SYSTEM_FLAGGED", true)
                .extractingByKey("RESOLVED_AT").isNotNull();
        assertThat(bioPageReviewRepository.countByResolvedAtIsNull()).isZero();
    }

    @Test
    void expiredHoldsAreDeletedAndLiveOnesKept() {
        bioHandleHoldRepository.saveAndFlush(hold("ended", null, NOW.minusDays(1)));
        bioHandleHoldRepository.saveAndFlush(hold("live", null, NOW.plusDays(1)));
        assertThat(bioHandleHoldRepository.findByHandleAndHeldUntilAfter("ended", NOW)).isEmpty();
        assertThat(bioHandleHoldRepository.findByHandleAndHeldUntilAfter("live", NOW)).isPresent();

        assertThat(bioHandleHoldRepository.deleteByHeldUntilBefore(NOW)).isEqualTo(1L);

        assertThat(bioHandleHoldRepository.findAll()).extracting(BioHandleHold::getHandle).containsExactly("live");
    }

    @Test
    void expiredHoldsAreDeletedInOneStatementThatLoadsNoHold() {
        bioHandleHoldRepository.saveAndFlush(hold("ended", null, NOW.minusDays(2)));
        bioHandleHoldRepository.saveAndFlush(hold("ended-too", null, NOW.minusMinutes(1)));
        bioHandleHoldRepository.saveAndFlush(hold("live", null, NOW.plusDays(1)));
        entityManager.clear();
        final var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        assertThat(bioHandleHoldRepository.deleteByHeldUntilBefore(NOW)).isEqualTo(2L);

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(jdbcTemplate.queryForList("select handle from bio_handle_hold", String.class)).containsExactly("live");
    }

    @Test
    void openSystemFlaggedLinksAreFound() {
        final var flagged = savedLink("flagged", null);
        final var reported = savedLink("reported", null);
        final var resolved = savedLink("resolved", null);
        linkReviewRepository.saveAndFlush(linkReview(flagged, true, null));
        linkReviewRepository.saveAndFlush(linkReview(reported, false, null));
        linkReviewRepository.saveAndFlush(linkReview(resolved, true, NOW));

        assertThat(linkReviewRepository.findOpenSystemFlaggedUrlIds(List.of(flagged.getId(), reported.getId(), resolved.getId())))
                .containsExactly(flagged.getId());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "ana, false, true, true",
            "ana, false, null, true",
            "null, false, true, false",
            "ana, true, true, false",
            "ana, false, false, false"
    })
    void aPageIsPublicWithAHandleUnhiddenAndAnOwnerNotDeactivated(final String handle, final boolean hidden, final Boolean ownerActive,
                                                                  final boolean expected) {
        final var page = BioPage.builder()
                .owner(User.builder().active(ownerActive).build())
                .handle(handle)
                .hiddenAt(hidden ? NOW : null)
                .build();

        assertThat(page.isPublic()).isEqualTo(expected);
    }

    private static String codePoints(final int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static ThrowingConsumer<Throwable> violates(final String constraint) {
        return exception -> assertThat(exception).isInstanceOfSatisfying(DataIntegrityViolationException.class,
                violation -> assertThat(violation.getMostSpecificCause().getMessage()).containsIgnoringCase(constraint));
    }

    private long statementsToLoadTheButtonsOf(final BioPage page) {
        entityManager.flush();
        entityManager.clear();
        final var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        bioPageButtonRepository.findByPageWithUrl(page).forEach(button -> button.getUrl().getDomain());
        final var statements = statistics.getPrepareStatementCount();
        entityManager.clear();
        return statements;
    }

    private long rowsOfPage(final String table, final BioPage page) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where page_id = ?", Long.class, page.getId());
    }

    private User savedUser(final String email) {
        return userRepository.saveAndFlush(User.builder().email(email).password("correct horse battery").apiKeySlots(4L).build());
    }

    private BioPage savedPage(final User owner, final String handle) {
        return bioPageRepository.saveAndFlush(page(owner, handle).build());
    }

    private Url savedLink(final String shortUrl, final User owner) {
        return urlRepository.saveAndFlush(Url.builder().shortUrl(shortUrl).longUrl("https://example.org/" + shortUrl).owner(owner).build());
    }

    private static BioPage.BioPageBuilder page(final User owner, final String handle) {
        return BioPage.builder().owner(owner).handle(handle);
    }

    private static BioPageButton button(final BioPage page, final Url url, final int sortOrder, final boolean visible) {
        return BioPageButton.builder().page(page).url(url).title("Button " + sortOrder).sortOrder(sortOrder).visible(visible).build();
    }

    private static BioHandleHold hold(final String handle, final BioPage page, final LocalDateTime heldUntil) {
        return BioHandleHold.builder().handle(handle).page(page).heldUntil(heldUntil).createdAt(NOW).build();
    }

    private static BioPageReview bioPageReview(final BioPage page, final LocalDateTime flaggedAt, final LocalDateTime resolvedAt) {
        return BioPageReview.builder().page(page).reasons("name imitates paypal").flaggedAt(flaggedAt).resolvedAt(resolvedAt)
                .resolution(resolvedAt != null ? BioPageReviewResolution.OK : null).systemFlagged(true).build();
    }

    private static LinkReview linkReview(final Url url, final boolean systemFlagged, final LocalDateTime resolvedAt) {
        return LinkReview.builder().url(url).reasons("domain is on a blocklist").systemFlagged(systemFlagged).resolvedAt(resolvedAt).build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BioPage.class)
    @EnableJpaRepositories(
            basePackageClasses = BioPageRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {
                            BioPageRepository.class,
                            BioPageButtonRepository.class,
                            BioHandleHoldRepository.class,
                            BioPageReviewRepository.class,
                            UrlRepository.class,
                            UserRepository.class,
                            LinkReviewRepository.class}))
    static class TestApplication {
    }

}
