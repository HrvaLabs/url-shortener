package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.dto.DataExportDto.BioButtonExport;
import cc.hrva.urlshortener.model.BioHandleHold;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.BioPageReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.BioPageReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.ReservedAliasService;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
@SpringBootTest(
        classes = BioAccountDataFlowTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:bio-account-data;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class BioAccountDataFlowTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-26T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 26);

    @Autowired
    private BioAccountData bioAccountData;

    @Autowired
    private BioPageRepository bioPageRepository;

    @Autowired
    private BioPageButtonRepository bioPageButtonRepository;

    @Autowired
    private BioHandleHoldRepository bioHandleHoldRepository;

    @Autowired
    private BioPageReviewRepository bioPageReviewRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private BioHandles bioHandles;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    @Test
    void deletingAnAccountRemovesThePageAndLeavesItsHandlesHeldUnlinked() {
        final var ana = savedUser("ana@example.com");
        final var bob = savedUser("bob@example.com");
        final var anasPage = savedPage(ana, "ana");
        final var bobsPage = savedPage(bob, "bob");
        bioPageButtonRepository.saveAndFlush(button(anasPage, savedLink("ana-shop", ana), "Shop", 0));
        bioPageButtonRepository.saveAndFlush(button(bobsPage, savedLink("bob-shop", bob), "Shop", 0));
        bioPageReviewRepository.saveAndFlush(BioPageReview.builder().page(anasPage).reasons("name imitates paypal").systemFlagged(true).build());
        jdbcTemplate.update("insert into bio_page_daily_views (page_id, stat_date, views) values (?, ?, ?)", anasPage.getId(), DAY, 3);
        bioHandleHoldRepository.saveAndFlush(hold("ana-old", anasPage, NOW.plusDays(10)));
        bioHandleHoldRepository.saveAndFlush(hold("ana", null, NOW.minusDays(2)));
        entityManager.clear();

        final var user = userRepository.findById(ana.getId()).orElseThrow();
        bioAccountData.deleteFor(user);
        urlRepository.deleteByOwner(user);
        userRepository.delete(user);
        entityManager.flush();

        assertThat(rows("select count(*) from user_account where id = ?", ana.getId())).isZero();
        assertThat(rows("select count(*) from bio_page where id = ?", anasPage.getId())).isZero();
        assertThat(rows("select count(*) from bio_page_button where page_id = ?", anasPage.getId())).isZero();
        assertThat(rows("select count(*) from bio_page_review where page_id = ?", anasPage.getId())).isZero();
        assertThat(rows("select count(*) from bio_page_daily_views where page_id = ?", anasPage.getId())).isZero();
        assertThat(jdbcTemplate.queryForList("select handle, page_id, held_until from bio_handle_hold order by handle"))
                .extracting(row -> row.get("HANDLE"), row -> row.get("PAGE_ID"), row -> ((Timestamp) row.get("HELD_UNTIL")).toLocalDateTime())
                .containsExactly(
                        tuple("ana", null, NOW.plusDays(30)),
                        tuple("ana-old", null, NOW.plusDays(10)));
        assertThat(rows("select count(*) from bio_page where id = ?", bobsPage.getId())).isEqualTo(1L);
        assertThat(rows("select count(*) from bio_page_button where page_id = ?", bobsPage.getId())).isEqualTo(1L);
        assertThat(bioHandles.unavailableReason("ana", bobsPage))
                .contains("This handle was recently used and is kept until November 25, 2026. Try another.");
        assertThat(bioHandles.unavailableReason("ana-old", null))
                .contains("This handle was recently used and is kept until November 5, 2026. Try another.");
    }

    @Test
    void theExportListsTheButtonsBySortOrderWithTheirAliases() {
        final var ana = savedUser("ana@example.com");
        final var page = savedPage(ana, "ana");
        bioPageButtonRepository.saveAndFlush(button(page, savedLink("ana-blog", ana), "Blog", 1));
        bioPageButtonRepository.saveAndFlush(button(page, savedLink("ana-shop", ana), "Shop", 0));
        entityManager.clear();

        final var export = bioAccountData.exportFor(userRepository.findById(ana.getId()).orElseThrow()).orElseThrow();

        assertThat(export.handle()).isEqualTo("ana");
        assertThat(export.buttons()).extracting(BioButtonExport::title, BioButtonExport::shortUrl)
                .containsExactly(tuple("Shop", "ana-shop"), tuple("Blog", "ana-blog"));
    }

    @Test
    void theExportKeepsTheBiosLineBreaks() {
        final var ana = savedUser("ana@example.com");
        bioPageRepository.saveAndFlush(BioPage.builder().owner(ana).handle("ana").bio("Photographer\nZagreb").build());
        entityManager.clear();

        final var export = bioAccountData.exportFor(userRepository.findById(ana.getId()).orElseThrow()).orElseThrow();

        assertThat(export.bio()).isEqualTo("Photographer\nZagreb");
    }

    private long rows(final String sql, final long id) {
        return jdbcTemplate.queryForObject(sql, Long.class, id);
    }

    private User savedUser(final String email) {
        return userRepository.saveAndFlush(User.builder().email(email).password("correct horse battery").apiKeySlots(4L).build());
    }

    private BioPage savedPage(final User owner, final String handle) {
        return bioPageRepository.saveAndFlush(BioPage.builder().owner(owner).handle(handle).build());
    }

    private Url savedLink(final String shortUrl, final User owner) {
        return urlRepository.saveAndFlush(Url.builder().shortUrl(shortUrl).longUrl("https://example.org/" + shortUrl).owner(owner).build());
    }

    private static BioPageButton button(final BioPage page, final Url url, final String title, final int sortOrder) {
        return BioPageButton.builder().page(page).url(url).title(title).sortOrder(sortOrder).build();
    }

    private static BioHandleHold hold(final String handle, final BioPage page, final LocalDateTime heldUntil) {
        return BioHandleHold.builder().handle(handle).page(page).heldUntil(heldUntil).createdAt(NOW.minusDays(1)).build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    BioPageRepository.class,
                    BioPageButtonRepository.class,
                    BioHandleHoldRepository.class,
                    BioPageReviewRepository.class,
                    UrlRepository.class,
                    UserRepository.class
            }))
    @Import({BioAccountData.class, BioPageLock.class, BioHandles.class})
    static class TestApplication {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
        }

    }

}
