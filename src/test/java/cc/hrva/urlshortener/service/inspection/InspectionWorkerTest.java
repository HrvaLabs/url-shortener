package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.client.BrowserRunClient;
import cc.hrva.urlshortener.client.PageSnapshot;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
class InspectionWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 11, 3, 10, 0);
    private static final long LINK = 9301L;
    private static final long OTHER_LINK = 9302L;
    private static final String LONG_URL = "https://findfavoritedate.life/?u=f6yp608";
    private static final String OTHER_LONG_URL = "https://njuskaio.cam/a/MAUB3";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    private static final String DAILY_LIMIT = "Today's screenshot time is used up. It's taken after midnight UTC.";
    private static final String BLOCKED_BEFORE = "The link was blocked before its screenshot was taken.";

    @Autowired
    private InspectionWorker inspectionWorker;

    @Autowired
    private BrowserRunClient browserRunClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        reset(browserRunClient);
        jdbcTemplate.update("delete from link_screenshot");
        jdbcTemplate.update("delete from link_inspection");
        jdbcTemplate.update("delete from url");
        link(LINK, "v0DB3", LONG_URL);
        link(OTHER_LINK, "njuskaio", OTHER_LONG_URL);
        clock.set(NOW);
        appProperties.getCloudflare().setAccountId("account-1");
        appProperties.getCloudflare().setBrowserApiToken("token-1");
        SettingsServiceFixtures.turnOn(settingsService, SettingKey.LINK_SCREENSHOTS);
        inspectionWorker.resume();
    }

    @Test
    void opensTheLongUrlNeverTheShortLink() {
        pending(LINK, NOW_UTC.minusMinutes(1), "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.taken(JPEG, "image/jpeg", "Find your date", 200));

        inspectionWorker.takeNextScreenshot();

        verify(browserRunClient).snapshot(LONG_URL);
        assertThat(row(LINK))
                .containsEntry("SCREENSHOT_STATE", "DONE")
                .containsEntry("SCREENSHOT_URL", LONG_URL)
                .containsEntry("PAGE_TITLE", "Find your date")
                .containsEntry("PAGE_STATUS", 200)
                .containsEntry("SCREENSHOT_FAILURE", null)
                .containsEntry("SCREENSHOT_TAKEN_AT", java.sql.Timestamp.valueOf(NOW_UTC));
        assertThat(jdbcTemplate.queryForObject("select image from link_screenshot where url_id = ?", byte[].class, LINK)).isEqualTo(JPEG);
        assertThat(jdbcTemplate.queryForObject("select content_type from link_screenshot where url_id = ?", String.class, LINK)).isEqualTo("image/jpeg");
    }

    @Test
    void aNewScreenshotReplacesTheOldOne() {
        pending(LINK, NOW_UTC.minusMinutes(1), "ADMIN");
        jdbcTemplate.update("insert into link_screenshot (url_id, image, content_type) values (?, ?, 'image/png')", LINK, new byte[]{9, 9});
        jdbcTemplate.update("update link_inspection set screenshot_failure = 'Old failure' where url_id = ?", LINK);
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.taken(JPEG, "image/jpeg", null, null));

        inspectionWorker.takeNextScreenshot();

        assertThat(jdbcTemplate.queryForObject("select image from link_screenshot where url_id = ?", byte[].class, LINK)).isEqualTo(JPEG);
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "DONE").containsEntry("SCREENSHOT_FAILURE", null).containsEntry("PAGE_TITLE", null);
    }

    @Test
    void aQueueRequestForABlockedLinkIsSkipped() {
        pending(LINK, NOW_UTC.minusMinutes(1), "QUEUE");
        jdbcTemplate.update("update url set active = false, block_reason = 'ADMIN' where id = ?", LINK);

        inspectionWorker.takeNextScreenshot();

        verifyNoInteractions(browserRunClient);
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "FAILED").containsEntry("SCREENSHOT_FAILURE", BLOCKED_BEFORE);
    }

    @Test
    void aQueueRequestForAHeldLinkIsTaken() {
        pending(LINK, NOW_UTC.minusMinutes(2), "QUEUE");
        pending(OTHER_LINK, NOW_UTC.minusMinutes(1), "QUEUE");
        jdbcTemplate.update("update url set active = false, block_reason = 'CAMPAIGN' where id = ?", LINK);
        jdbcTemplate.update("update url set active = false, block_reason = 'SIGN_IN_FORM' where id = ?", OTHER_LINK);
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.taken(JPEG, "image/jpeg", null, 200));

        inspectionWorker.takeNextScreenshot();
        inspectionWorker.takeNextScreenshot();

        verify(browserRunClient).snapshot(LONG_URL);
        verify(browserRunClient).snapshot(OTHER_LONG_URL);
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "DONE");
        assertThat(row(OTHER_LINK)).containsEntry("SCREENSHOT_STATE", "DONE");
    }

    @Test
    void aHoldThatFoundMalwareCountsAsBlocked() {
        pending(LINK, NOW_UTC.minusMinutes(1), "QUEUE");
        jdbcTemplate.update("update url set active = false, block_reason = 'SIGN_IN_FORM', threat_type = 'MALWARE' where id = ?", LINK);

        inspectionWorker.takeNextScreenshot();

        verifyNoInteractions(browserRunClient);
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "FAILED").containsEntry("SCREENSHOT_FAILURE", BLOCKED_BEFORE);
    }

    @Test
    void anAdminRequestForABlockedLinkIsTaken() {
        pending(LINK, NOW_UTC.minusMinutes(1), "ADMIN");
        jdbcTemplate.update("update url set active = false, block_reason = 'ADMIN' where id = ?", LINK);
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.taken(JPEG, "image/jpeg", "Blocked page", 200));

        inspectionWorker.takeNextScreenshot();

        verify(browserRunClient).snapshot(LONG_URL);
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "DONE");
    }

    @Test
    void takesTheOldestPendingFirst() {
        pending(LINK, NOW_UTC.minusMinutes(1), "QUEUE");
        pending(OTHER_LINK, NOW_UTC.minusMinutes(5), "QUEUE");
        jdbcTemplate.update("insert into url (id, short_url, long_url, active, visits) values (9303, 'done', 'https://done.example/', true, 0)");
        jdbcTemplate.update("insert into link_inspection (url_id, screenshot_state, screenshot_requested_at, screenshot_requested_by) values (9303, 'FAILED', ?, 'QUEUE')",
                NOW_UTC.minusMinutes(9));
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.taken(JPEG, "image/jpeg", null, 200));

        inspectionWorker.takeNextScreenshot();
        inspectionWorker.takeNextScreenshot();
        inspectionWorker.takeNextScreenshot();

        final InOrder order = inOrder(browserRunClient);
        order.verify(browserRunClient).snapshot(OTHER_LONG_URL);
        order.verify(browserRunClient).snapshot(LONG_URL);
        verify(browserRunClient, times(2)).snapshot(anyString());
    }

    @Test
    void dailyLimitKeepsTheRequestPendingUntilMidnightUtc() {
        clock.set(Instant.parse("2026-11-03T22:15:00Z"));
        pending(LINK, NOW_UTC, "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.dailyLimit());

        inspectionWorker.takeNextScreenshot();

        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING").containsEntry("SCREENSHOT_FAILURE", DAILY_LIMIT);
        clock.set(Instant.parse("2026-11-03T23:59:59Z"));
        inspectionWorker.takeNextScreenshot();
        verify(browserRunClient, times(1)).snapshot(anyString());

        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.taken(JPEG, "image/jpeg", null, 200));
        clock.set(Instant.parse("2026-11-04T00:00:00Z"));
        inspectionWorker.takeNextScreenshot();
        verify(browserRunClient, times(2)).snapshot(anyString());
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "DONE").containsEntry("SCREENSHOT_FAILURE", null);
    }

    @Test
    void rateLimitWaitsForRetryAfter() {
        pending(LINK, NOW_UTC, "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.rateLimited(Duration.ofSeconds(30)));

        inspectionWorker.takeNextScreenshot();

        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING").containsEntry("SCREENSHOT_FAILURE", null);
        clock.advance(Duration.ofSeconds(29));
        inspectionWorker.takeNextScreenshot();
        verify(browserRunClient, times(1)).snapshot(anyString());

        clock.advance(Duration.ofSeconds(1));
        inspectionWorker.takeNextScreenshot();
        verify(browserRunClient, times(2)).snapshot(anyString());
    }

    @Test
    void aFailureIsStoredWithItsReason() {
        pending(LINK, NOW_UTC, "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.failed("The page didn't load within 20 seconds."));

        inspectionWorker.takeNextScreenshot();

        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "FAILED")
                .containsEntry("SCREENSHOT_FAILURE", "The page didn't load within 20 seconds.")
                .containsEntry("SCREENSHOT_TAKEN_AT", null);
        inspectionWorker.takeNextScreenshot();
        verify(browserRunClient, times(1)).snapshot(anyString());
    }

    @Test
    void notConfiguredLeavesTheRequestWaiting() {
        pending(LINK, NOW_UTC, "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.notConfigured());

        inspectionWorker.takeNextScreenshot();

        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING");
    }

    @Test
    void nothingIsOpenedWhileScreenshotsAreOffOrNotSetUp() {
        pending(LINK, NOW_UTC, "ADMIN");

        SettingsServiceFixtures.turnOff(settingsService, SettingKey.LINK_SCREENSHOTS);
        inspectionWorker.takeNextScreenshot();
        SettingsServiceFixtures.turnOn(settingsService, SettingKey.LINK_SCREENSHOTS);
        appProperties.getCloudflare().setAccountId("");
        inspectionWorker.takeNextScreenshot();

        verify(browserRunClient, never()).snapshot(anyString());
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING");
    }

    @Test
    void aLinkRepointedWhileItsScreenshotIsTakenKeepsNoStaleImage() {
        pending(LINK, NOW_UTC, "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenAnswer(invocation -> {
            jdbcTemplate.update("delete from link_inspection where url_id = ?", LINK);
            jdbcTemplate.update("insert into link_inspection (url_id, screenshot_state, screenshot_requested_at, screenshot_requested_by) values (?, 'PENDING', ?, 'QUEUE')",
                    LINK, NOW_UTC.plusSeconds(5));
            return PageSnapshot.taken(JPEG, "image/jpeg", "Old page", 200);
        });

        inspectionWorker.takeNextScreenshot();

        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING").containsEntry("PAGE_TITLE", null);
        assertThat(jdbcTemplate.queryForObject("select count(*) from link_screenshot", Integer.class)).isZero();
    }

    @Test
    void aLinkDroppedWhileItsScreenshotIsTakenStaysDropped() {
        pending(LINK, NOW_UTC, "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenAnswer(invocation -> {
            jdbcTemplate.update("delete from link_inspection where url_id = ?", LINK);
            return PageSnapshot.failed("The screenshot was too large.");
        });

        inspectionWorker.takeNextScreenshot();

        assertThat(jdbcTemplate.queryForObject("select count(*) from link_inspection", Integer.class)).isZero();
    }

    @Test
    void anExceptionNeverEscapesTheTick() {
        pending(LINK, NOW_UTC, "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> inspectionWorker.takeNextScreenshot()).doesNotThrowAnyException();
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING");

        jdbcTemplate.execute("alter table link_screenshot rename to link_screenshot_gone");
        try {
            assertThatCode(() -> inspectionWorker.expireOldScreenshots()).doesNotThrowAnyException();
        } finally {
            jdbcTemplate.execute("alter table link_screenshot_gone rename to link_screenshot");
        }
    }

    @Test
    void anExceptionPausesTheWorkerForAMinute() {
        pending(LINK, NOW_UTC, "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenThrow(new IllegalStateException("boom"));

        inspectionWorker.takeNextScreenshot();
        clock.advance(Duration.ofSeconds(59));
        inspectionWorker.takeNextScreenshot();

        verify(browserRunClient, times(1)).snapshot(anyString());
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING");
        clock.advance(Duration.ofSeconds(1));
        inspectionWorker.takeNextScreenshot();
        verify(browserRunClient, times(2)).snapshot(anyString());
    }

    @Test
    void aRequestThatFailsTwiceInARowIsMarkedFailedAndTheQueueMovesOn() {
        pending(LINK, NOW_UTC.minusMinutes(2), "QUEUE");
        pending(OTHER_LINK, NOW_UTC.minusMinutes(1), "QUEUE");
        when(browserRunClient.snapshot(LONG_URL)).thenThrow(new IllegalStateException("boom"));
        when(browserRunClient.snapshot(OTHER_LONG_URL)).thenReturn(PageSnapshot.taken(JPEG, "image/jpeg", null, 200));

        inspectionWorker.takeNextScreenshot();
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING");
        clock.advance(Duration.ofMinutes(1));
        inspectionWorker.takeNextScreenshot();
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "FAILED")
                .containsEntry("SCREENSHOT_FAILURE", "Taking the screenshot failed. Take it again to retry.");

        clock.advance(Duration.ofMinutes(1));
        inspectionWorker.takeNextScreenshot();
        verify(browserRunClient).snapshot(OTHER_LONG_URL);
        assertThat(row(OTHER_LINK)).containsEntry("SCREENSHOT_STATE", "DONE");
    }

    @Test
    void anExceptionWhileStoringIsRetriedOnceThenMarkedFailed() {
        pending(LINK, NOW_UTC, "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.taken(JPEG, "image/jpeg", null, 200));
        jdbcTemplate.execute("alter table link_screenshot rename to link_screenshot_gone");
        try {
            inspectionWorker.takeNextScreenshot();
            assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING");
            clock.advance(Duration.ofMinutes(1));
            inspectionWorker.takeNextScreenshot();
        } finally {
            jdbcTemplate.execute("alter table link_screenshot_gone rename to link_screenshot");
        }

        verify(browserRunClient, times(2)).snapshot(anyString());
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "FAILED")
                .containsEntry("SCREENSHOT_FAILURE", "Taking the screenshot failed. Take it again to retry.");
    }

    @Test
    void aSuccessInBetweenStartsTheCountAgain() {
        pending(LINK, NOW_UTC, "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenThrow(new IllegalStateException("boom"))
                .thenReturn(PageSnapshot.rateLimited(Duration.ofSeconds(10)))
                .thenThrow(new IllegalStateException("boom"));

        inspectionWorker.takeNextScreenshot();
        clock.advance(Duration.ofMinutes(1));
        inspectionWorker.takeNextScreenshot();
        clock.advance(Duration.ofMinutes(1));
        inspectionWorker.takeNextScreenshot();

        verify(browserRunClient, times(3)).snapshot(anyString());
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING");
    }

    @Test
    void aRefusedTokenKeepsTheRequestPendingAndPausesForAnHour() {
        pending(LINK, NOW_UTC.minusMinutes(2), "QUEUE");
        pending(OTHER_LINK, NOW_UTC.minusMinutes(1), "QUEUE");
        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.tokenRefused());
        final var logger = (Logger) LoggerFactory.getLogger(InspectionWorker.class);
        final var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            inspectionWorker.takeNextScreenshot();
            clock.advance(Duration.ofMinutes(59));
            inspectionWorker.takeNextScreenshot();
        } finally {
            logger.detachAppender(events);
        }

        verify(browserRunClient, times(1)).snapshot(anyString());
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "PENDING").containsEntry("SCREENSHOT_FAILURE", "Cloudflare refused the API token.");
        assertThat(row(OTHER_LINK)).containsEntry("SCREENSHOT_STATE", "PENDING").containsEntry("SCREENSHOT_FAILURE", null);
        assertThat(events.list).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(1);

        when(browserRunClient.snapshot(anyString())).thenReturn(PageSnapshot.taken(JPEG, "image/jpeg", null, 200));
        clock.advance(Duration.ofMinutes(1));
        inspectionWorker.takeNextScreenshot();
        verify(browserRunClient, times(2)).snapshot(LONG_URL);
        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "DONE").containsEntry("SCREENSHOT_FAILURE", null);
    }

    @Test
    void screenshotsOlderThanSevenDaysExpire() {
        done(LINK, NOW_UTC.minusDays(7).minusMinutes(1));
        done(OTHER_LINK, NOW_UTC.minusDays(7).plusMinutes(1));

        inspectionWorker.expireOldScreenshots();

        assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "EXPIRED");
        assertThat(row(OTHER_LINK)).containsEntry("SCREENSHOT_STATE", "DONE");
        assertThat(jdbcTemplate.queryForList("select url_id from link_screenshot", Long.class)).containsExactly(OTHER_LINK);
    }

    @Test
    void theKeptDaysComeFromConfiguration() {
        appProperties.getInspection().setKeepScreenshotDays(2);
        try {
            done(LINK, NOW_UTC.minusDays(3));

            inspectionWorker.expireOldScreenshots();

            assertThat(row(LINK)).containsEntry("SCREENSHOT_STATE", "EXPIRED");
        } finally {
            appProperties.getInspection().setKeepScreenshotDays(7);
        }
    }

    @Test
    void theJobsRunOnTheirSchedules() throws NoSuchMethodException {
        final Method tick = InspectionWorker.class.getMethod("takeNextScreenshot");
        final Method expire = InspectionWorker.class.getMethod("expireOldScreenshots");

        assertThat(tick.getAnnotation(Scheduled.class).fixedDelayString()).isEqualTo("${app.inspection.interval:15s}");
        assertThat(expire.getAnnotation(Scheduled.class).cron()).isEqualTo("0 30 3 * * *");
        assertThat(expire.getAnnotation(Scheduled.class).zone()).isEqualTo("UTC");
    }

    private void link(final long id, final String shortUrl, final String longUrl) {
        jdbcTemplate.update("insert into url (id, short_url, long_url, active, visits) values (?, ?, ?, true, 0)", id, shortUrl, longUrl);
    }

    private void pending(final long urlId, final LocalDateTime requestedAt, final String requestedBy) {
        jdbcTemplate.update("insert into link_inspection (url_id, screenshot_state, screenshot_requested_at, screenshot_requested_by) values (?, 'PENDING', ?, ?)",
                urlId, requestedAt, requestedBy);
    }

    private void done(final long urlId, final LocalDateTime takenAt) {
        jdbcTemplate.update("insert into link_inspection (url_id, screenshot_state, screenshot_requested_at, screenshot_requested_by, screenshot_taken_at) "
                + "values (?, 'DONE', ?, 'QUEUE', ?)", urlId, takenAt.minusMinutes(1), takenAt);
        jdbcTemplate.update("insert into link_screenshot (url_id, image, content_type) values (?, ?, 'image/jpeg')", urlId, JPEG);
    }

    private Map<String, Object> row(final long urlId) {
        return jdbcTemplate.queryForMap("select * from link_inspection where url_id = ?", urlId);
    }

}
