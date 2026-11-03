package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.inspection.ScreenshotView;
import cc.hrva.urlshortener.exception.ScreenshotsUnavailableException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.LinkScreenshot;
import cc.hrva.urlshortener.model.enums.InspectionState;
import cc.hrva.urlshortener.repository.LinkInspectionRepository;
import cc.hrva.urlshortener.repository.LinkScreenshotRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.alert.LinkReviewOpened;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

@SpringBootTest(
        classes = InspectionTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                InspectionTestApplication.DATABASE_URL,
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=https://hrva.cc",
                "app.mcp.signing-key=test-only"
        })
class DefaultLinkInspectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 11, 3, 10, 0);
    private static final long LINK = 9201L;
    private static final long OTHER_LINK = 9202L;
    private static final long THIRD_LINK = 9203L;
    private static final String LONG_URL = "https://findfavoritedate.life/?u=f6yp608";

    @Autowired
    private LinkInspectionService linkInspectionService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from link_screenshot");
        jdbcTemplate.update("delete from link_inspection");
        jdbcTemplate.update("delete from url");
        link(LINK, "v0DB3", LONG_URL, "https://findfavoritedate.life/?u=f6yp608\nhttps://findfavoritedate.life/start\nhttps://sso.findfavoritedate.life/");
        link(OTHER_LINK, "njuskaio", "https://njuskaio.cam/a/MAUB3", null);
        link(THIRD_LINK, "utn", "https://utn.pl/x", null);
        clock.set(NOW);
        appProperties.getCloudflare().setAccountId("account-1");
        appProperties.getCloudflare().setBrowserApiToken("token-1");
        SettingsServiceFixtures.turnOn(settingsService, SettingKey.LINK_SCREENSHOTS);
    }

    @Test
    void the049ChangesetsRanAfter048() {
        assertThat(jdbcTemplate.queryForList("select id from databasechangelog where id like '048-%' or id like '049-%' order by orderexecuted",
                String.class))
                .containsExactly("048-url-redirect-chain", "049-link-inspection", "049-link-screenshot");
        assertThat(jdbcTemplate.queryForList("select index_name from information_schema.indexes where upper(table_name) = 'LINK_INSPECTION'", String.class))
                .anySatisfy(name -> assertThat(name).isEqualToIgnoringCase("idx_link_inspection_screenshot_state"));
    }

    @Test
    void reviewOpenedQueuesAScreenshotAfterCommit() {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new LinkReviewOpened(LINK));
            assertThat(rows(LINK)).isZero();
        });

        assertThat(jdbcTemplate.queryForMap("select screenshot_state, screenshot_requested_by, screenshot_requested_at, screenshot_failure "
                + "from link_inspection where url_id = ?", LINK))
                .containsEntry("SCREENSHOT_STATE", "PENDING")
                .containsEntry("SCREENSHOT_REQUESTED_BY", "QUEUE")
                .containsEntry("SCREENSHOT_REQUESTED_AT", java.sql.Timestamp.valueOf(NOW_UTC))
                .containsEntry("SCREENSHOT_FAILURE", null);
    }

    @Test
    void aReviewThatRollsBackQueuesNothing() {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new LinkReviewOpened(LINK));
            status.setRollbackOnly();
        });

        assertThat(rows(LINK)).isZero();
    }

    @Test
    void aReviewForALinkDeletedMeanwhileQueuesNothingAndThrowsNothing() {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new LinkReviewOpened(9999L)));

        assertThat(jdbcTemplate.queryForObject("select count(*) from link_inspection", Integer.class)).isZero();
    }

    @Test
    void aSecondRequestWhilePendingIsANoOp() {
        linkInspectionService.requestScreenshot(LINK);
        clock.advance(Duration.ofMinutes(5));

        linkInspectionService.requestScreenshot(LINK);

        assertThat(requestedAt(LINK)).isEqualTo(NOW_UTC);
        assertThat(requestedBy(LINK)).isEqualTo("QUEUE");
    }

    @Test
    void anAdminAskingWhileAQueueRequestWaitsMakesItTheirsWithoutMovingItBack() {
        linkInspectionService.requestScreenshot(LINK);
        clock.advance(Duration.ofMinutes(5));

        linkInspectionService.takeScreenshot(LINK);

        assertThat(requestedAt(LINK)).isEqualTo(NOW_UTC);
        assertThat(requestedBy(LINK)).isEqualTo("ADMIN");
    }

    @Test
    void anAdminTakesAnotherScreenshotOfADoneOneAndKeepsTheOldImageUntilItArrives() {
        inspection(LINK, "DONE", NOW_UTC.minusDays(1), NOW_UTC.minusDays(1), null);
        screenshot(LINK, new byte[]{1, 2, 3});
        jdbcTemplate.update("update link_inspection set screenshot_failure = 'old' where url_id = ?", LINK);

        linkInspectionService.takeScreenshot(LINK);

        assertThat(jdbcTemplate.queryForMap("select screenshot_state, screenshot_requested_by, screenshot_requested_at, screenshot_failure, "
                + "screenshot_taken_at from link_inspection where url_id = ?", LINK))
                .containsEntry("SCREENSHOT_STATE", "PENDING")
                .containsEntry("SCREENSHOT_REQUESTED_BY", "ADMIN")
                .containsEntry("SCREENSHOT_REQUESTED_AT", java.sql.Timestamp.valueOf(NOW_UTC))
                .containsEntry("SCREENSHOT_FAILURE", null)
                .containsEntry("SCREENSHOT_TAKEN_AT", java.sql.Timestamp.valueOf(NOW_UTC.minusDays(1)));
        assertThat(linkInspectionService.screenshot(LINK)).map(LinkScreenshot::getImage).contains(new byte[]{1, 2, 3});
    }

    @Test
    void withoutCredentialsNothingIsQueued() {
        appProperties.getCloudflare().setBrowserApiToken("");

        linkInspectionService.requestScreenshot(LINK);
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new LinkReviewOpened(LINK)));

        assertThat(rows(LINK)).isZero();
        assertThatThrownBy(() -> linkInspectionService.takeScreenshot(LINK))
                .isInstanceOf(ScreenshotsUnavailableException.class)
                .hasMessage("Screenshots aren't set up.");
        assertThat(linkInspectionService.inspection(LINK).screenshot().configured()).isFalse();

        appProperties.getCloudflare().setBrowserApiToken("token-1");
        appProperties.getCloudflare().setAccountId(" ");
        linkInspectionService.requestScreenshot(LINK);
        assertThat(rows(LINK)).isZero();
    }

    @Test
    void settingOffNothingIsQueued() {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.LINK_SCREENSHOTS);

        linkInspectionService.requestScreenshot(LINK);
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new LinkReviewOpened(LINK)));

        assertThat(rows(LINK)).isZero();
        assertThatThrownBy(() -> linkInspectionService.takeScreenshot(LINK))
                .isInstanceOf(ScreenshotsUnavailableException.class)
                .hasMessage("Screenshots aren't set up.");
        assertThat(linkInspectionService.inspection(LINK).screenshot().configured()).isFalse();
    }

    @Test
    void anAdminRequestForAnUnknownLinkIsNotFound() {
        assertThatThrownBy(() -> linkInspectionService.takeScreenshot(9999L))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessage("Link not found.");
        assertThatThrownBy(() -> linkInspectionService.inspection(9999L))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessage("Link not found.");
    }

    @Test
    void dropForDeletesTheInspectionAndTheScreenshot() {
        inspection(LINK, "DONE", NOW_UTC, NOW_UTC, null);
        screenshot(LINK, new byte[]{1});
        inspection(OTHER_LINK, "DONE", NOW_UTC, NOW_UTC, null);
        screenshot(OTHER_LINK, new byte[]{2});

        transactionTemplate.executeWithoutResult(status -> linkInspectionService.dropFor(LINK));

        assertThat(rows(LINK)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from link_screenshot where url_id = ?", Integer.class, LINK)).isZero();
        assertThat(rows(OTHER_LINK)).isOne();
        assertThat(linkInspectionService.screenshot(OTHER_LINK)).isPresent();
    }

    @Test
    void dropForDeletesTheInspectionBeforeTheScreenshot() {
        final var inspections = mock(LinkInspectionRepository.class);
        final var screenshots = mock(LinkScreenshotRepository.class);
        final var service = new DefaultLinkInspectionService(inspections, screenshots, mock(UrlRepository.class),
                settingsService, mock(PlatformTransactionManager.class), clock);

        service.dropFor(LINK);

        final var order = inOrder(inspections, screenshots);
        order.verify(inspections).deleteByUrlId(LINK);
        order.verify(screenshots).deleteByUrlId(LINK);
    }

    @Test
    void deletingTheLinkDeletesItsInspectionAndScreenshot() {
        inspection(LINK, "DONE", NOW_UTC, NOW_UTC, null);
        screenshot(LINK, new byte[]{1});

        jdbcTemplate.update("delete from url where id = ?", LINK);

        assertThat(rows(LINK)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from link_screenshot", Integer.class)).isZero();
    }

    @Test
    void theInspectionAlwaysAnswersWithTheLongUrlAndTheChain() {
        final var nothingYet = linkInspectionService.inspection(OTHER_LINK);

        assertThat(nothingYet.urlId()).isEqualTo(OTHER_LINK);
        assertThat(nothingYet.longUrl()).isEqualTo("https://njuskaio.cam/a/MAUB3");
        assertThat(nothingYet.redirectChain()).isEmpty();
        assertThat(nothingYet.screenshot()).isEqualTo(new ScreenshotView(null, null, null, null, null, null, null, true));

        inspection(LINK, "DONE", NOW_UTC.minusMinutes(2), NOW_UTC.minusMinutes(1), null);
        jdbcTemplate.update("update link_inspection set screenshot_url = ?, page_title = 'Sign in', page_status = 200 where url_id = ?", LONG_URL, LINK);

        final var taken = linkInspectionService.inspection(LINK);

        assertThat(taken.redirectChain()).containsExactly(LONG_URL, "https://findfavoritedate.life/start", "https://sso.findfavoritedate.life/");
        assertThat(taken.screenshot()).isEqualTo(new ScreenshotView(InspectionState.DONE, NOW_UTC.minusMinutes(2), NOW_UTC.minusMinutes(1), LONG_URL,
                "Sign in", 200, null, true));
    }

    @Test
    void summariesNeverLoadTheImage() {
        inspection(LINK, "DONE", NOW_UTC.minusMinutes(2), NOW_UTC.minusMinutes(1), null);
        screenshot(LINK, new byte[]{1, 2, 3});
        inspection(OTHER_LINK, "FAILED", NOW_UTC.minusMinutes(3), null, "The page didn't load within 20 seconds.");
        final var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        final var summaries = linkInspectionService.summaries(List.of(LINK, OTHER_LINK, THIRD_LINK));

        assertThat(statistics.getPrepareStatementCount()).isOne();
        assertThat(statistics.getEntityStatistics(LinkScreenshot.class.getName()).getLoadCount()).isZero();
        assertThat(summaries).containsOnlyKeys(LINK, OTHER_LINK, THIRD_LINK);
        assertThat(summaries.get(LINK).state()).isEqualTo(InspectionState.DONE);
        assertThat(summaries.get(OTHER_LINK)).isEqualTo(new ScreenshotView(InspectionState.FAILED, NOW_UTC.minusMinutes(3), null, null, null, null,
                "The page didn't load within 20 seconds.", true));
        assertThat(summaries.get(THIRD_LINK)).isEqualTo(new ScreenshotView(null, null, null, null, null, null, null, true));
    }

    @Test
    void summariesOfNoLinksAskNothing() {
        final var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        assertThat(linkInspectionService.summaries(List.of())).isEqualTo(Map.of());
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    void aRowWithoutAScreenshotStateReadsAsNoScreenshotYet() {
        jdbcTemplate.update("insert into link_inspection (url_id) values (?)", LINK);

        assertThat(linkInspectionService.inspection(LINK).screenshot()).isEqualTo(new ScreenshotView(null, null, null, null, null, null, null, true));
        linkInspectionService.requestScreenshot(LINK);
        assertThat(requestedBy(LINK)).isEqualTo("QUEUE");
    }

    private void link(final long id, final String shortUrl, final String longUrl, final String redirectChain) {
        jdbcTemplate.update("insert into url (id, short_url, long_url, active, visits, redirect_chain) values (?, ?, ?, true, 0, ?)", id, shortUrl,
                longUrl, redirectChain);
    }

    private void inspection(final long urlId, final String state, final LocalDateTime requestedAt, final LocalDateTime takenAt, final String failure) {
        jdbcTemplate.update("insert into link_inspection (url_id, screenshot_state, screenshot_requested_at, screenshot_requested_by, screenshot_taken_at, "
                + "screenshot_failure) values (?, ?, ?, 'QUEUE', ?, ?)", urlId, state, requestedAt, takenAt, failure);
    }

    private void screenshot(final long urlId, final byte[] image) {
        jdbcTemplate.update("insert into link_screenshot (url_id, image, content_type) values (?, ?, 'image/jpeg')", urlId, image);
    }

    private int rows(final long urlId) {
        return jdbcTemplate.queryForObject("select count(*) from link_inspection where url_id = ?", Integer.class, urlId);
    }

    private LocalDateTime requestedAt(final long urlId) {
        return jdbcTemplate.queryForObject("select screenshot_requested_at from link_inspection where url_id = ?", LocalDateTime.class, urlId);
    }

    private String requestedBy(final long urlId) {
        return jdbcTemplate.queryForObject("select screenshot_requested_by from link_inspection where url_id = ?", String.class, urlId);
    }

}
