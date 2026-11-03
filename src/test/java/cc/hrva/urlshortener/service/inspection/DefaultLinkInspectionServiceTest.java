package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.audit.AuditSubjects;
import cc.hrva.urlshortener.client.VirusTotalClient;
import cc.hrva.urlshortener.client.VirusTotalReport;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.inspection.ScreenshotView;
import cc.hrva.urlshortener.dto.inspection.VirusTotalView;
import cc.hrva.urlshortener.exception.ScreenshotsUnavailableException;
import cc.hrva.urlshortener.exception.ServiceBusyException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.VirusTotalUnavailableException;
import cc.hrva.urlshortener.model.LinkInspection;
import cc.hrva.urlshortener.model.LinkScreenshot;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.InspectionState;
import cc.hrva.urlshortener.repository.LinkInspectionRepository;
import cc.hrva.urlshortener.repository.LinkScreenshotRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.alert.LinkReviewOpened;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
class DefaultLinkInspectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 11, 3, 10, 0);
    private static final long LINK = 9201L;
    private static final long OTHER_LINK = 9202L;
    private static final long THIRD_LINK = 9203L;
    private static final String LONG_URL = "https://findfavoritedate.life/?u=f6yp608";
    private static final String ADMIN = "admin@hrva.cc";
    private static final String SUBJECT = "link: hrva.cc/v0DB3, destination: " + LONG_URL;
    private static final String REPORT_ID = "1db0ad7dbcec0676710ea0eaacd35d5e471d3e11944d53bcbd31f0cbd11bce31";
    private static final String NOT_SET_UP = "VirusTotal isn't set up.";
    private static final String USED_UP = "Today's VirusTotal lookups are used up. Try again after midnight UTC.";

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

    @Autowired
    private VirusTotalClient virusTotalClient;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditSubjects auditSubjects;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private InMemoryRedis inMemoryRedis;

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
        appProperties.getInspection().setVirusTotalApiKey("vt-key-1");
        appProperties.getInspection().setVirusTotalDailyLimit(480);
        SettingsServiceFixtures.turnOn(settingsService, SettingKey.VIRUSTOTAL_LOOKUPS);
        reset(virusTotalClient, auditLogService, auditSubjects);
        when(auditSubjects.describe("Url", String.valueOf(LINK))).thenReturn(Optional.of(SUBJECT));
        inMemoryRedis.expireKeysContaining("");
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
    void the050ChangesetRanAfter049() {
        assertThat(jdbcTemplate.queryForList("select id from databasechangelog where id like '049-%' or id like '050-%' order by orderexecuted",
                String.class))
                .containsExactly("049-link-inspection", "049-link-screenshot", "050-virustotal");
        assertThat(jdbcTemplate.queryForList("select index_name from information_schema.indexes where upper(table_name) = 'LINK_INSPECTION'", String.class))
                .anySatisfy(name -> assertThat(name).isEqualToIgnoringCase("idx_link_inspection_vt_state"));
    }

    @Test
    void reviewOpenedQueuesBoth() {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new LinkReviewOpened(LINK)));

        assertThat(jdbcTemplate.queryForMap("select screenshot_state, vt_state, vt_requested_at, vt_failure from link_inspection where url_id = ?", LINK))
                .containsEntry("SCREENSHOT_STATE", "PENDING")
                .containsEntry("VT_STATE", "PENDING")
                .containsEntry("VT_REQUESTED_AT", java.sql.Timestamp.valueOf(NOW_UTC))
                .containsEntry("VT_FAILURE", null);
        verify(virusTotalClient, never()).submit(anyString());
        verify(virusTotalClient, never()).lookup(anyString());
    }

    @Test
    void withoutAKeyNothingIsQueued() {
        appProperties.getInspection().setVirusTotalApiKey(" ");

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new LinkReviewOpened(LINK)));
        linkInspectionService.requestVirusTotal(OTHER_LINK);

        assertThat(jdbcTemplate.queryForMap("select screenshot_state, vt_state from link_inspection where url_id = ?", LINK))
                .containsEntry("SCREENSHOT_STATE", "PENDING")
                .containsEntry("VT_STATE", null);
        assertThat(rows(OTHER_LINK)).isZero();
        assertThat(linkInspectionService.inspection(LINK).virusTotal().configured()).isFalse();
        assertThatThrownBy(() -> linkInspectionService.submitToVirusTotal(LINK, ADMIN))
                .isInstanceOf(VirusTotalUnavailableException.class)
                .hasMessage(NOT_SET_UP);
        verifyNoVirusTotalCall();
    }

    @Test
    void withLookupsOffNothingIsQueued() {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.VIRUSTOTAL_LOOKUPS);

        linkInspectionService.requestVirusTotal(LINK);

        assertThat(rows(LINK)).isZero();
        assertThatThrownBy(() -> linkInspectionService.submitToVirusTotal(LINK, ADMIN))
                .isInstanceOf(VirusTotalUnavailableException.class)
                .hasMessage(NOT_SET_UP);
        assertThat(linkInspectionService.inspection(LINK).virusTotal().configured()).isFalse();
        verifyNoVirusTotalCall();
    }

    @Test
    void aSecondVirusTotalRequestWhilePendingIsANoOp() {
        linkInspectionService.requestVirusTotal(LINK);
        clock.advance(Duration.ofMinutes(5));

        linkInspectionService.requestVirusTotal(LINK);

        assertThat(jdbcTemplate.queryForObject("select vt_requested_at from link_inspection where url_id = ?", LocalDateTime.class, LINK))
                .isEqualTo(NOW_UTC);
    }

    @Test
    void aVirusTotalRequestThatRacesAnotherInsertIsRetriedOnce() {
        final var inspections = mock(LinkInspectionRepository.class);
        final var urls = mock(UrlRepository.class);
        when(urls.existsById(LINK)).thenReturn(true);
        when(inspections.findById(LINK)).thenReturn(Optional.empty());
        when(inspections.saveAndFlush(any(LinkInspection.class))).thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        serviceOver(inspections, mock(LinkScreenshotRepository.class), urls).requestVirusTotal(LINK);

        verify(inspections, times(2)).saveAndFlush(any(LinkInspection.class));
    }

    @Test
    void aScreenshotRequestThatFailsStillQueuesTheLookup() {
        final var inspections = mock(LinkInspectionRepository.class);
        final var urls = mock(UrlRepository.class);
        when(urls.existsById(LINK)).thenThrow(new IllegalStateException("boom")).thenReturn(true);
        when(inspections.findById(LINK)).thenReturn(Optional.empty());

        serviceOver(inspections, mock(LinkScreenshotRepository.class), urls).onReviewOpened(new LinkReviewOpened(LINK));

        verify(inspections).saveAndFlush(org.mockito.ArgumentMatchers.argThat(inspection -> inspection.getVtState() == InspectionState.PENDING));
    }

    @Test
    void submitAudits() {
        when(virusTotalClient.submit(LONG_URL)).thenReturn(VirusTotalReport.submitted());

        final var view = linkInspectionService.submitToVirusTotal(LINK, ADMIN);

        assertThat(view.state()).isEqualTo(InspectionState.PENDING);
        assertThat(view.submittedAt()).isEqualTo(NOW_UTC);
        assertThat(view.configured()).isTrue();
        verify(auditLogService).log(ADMIN, AuditAction.SUBMIT_TO_VIRUSTOTAL, "Url", String.valueOf(LINK), SUBJECT);
        assertThat(rateLimiter.count("virustotal:2026-11-03")).hasValue(1);
    }

    @Test
    void submitKeepsTheOldVerdictUntilTheNewOneArrives() {
        verdict(LINK, "DONE", REPORT_ID, 4);
        jdbcTemplate.update("update link_inspection set vt_failure = 'old' where url_id = ?", LINK);
        when(virusTotalClient.submit(LONG_URL)).thenReturn(VirusTotalReport.submitted());

        final var view = linkInspectionService.submitToVirusTotal(LINK, ADMIN);

        assertThat(view.malicious()).isEqualTo(4);
        assertThat(view.reportUrl()).isEqualTo("https://www.virustotal.com/gui/url/" + REPORT_ID);
        assertThat(view.failure()).isNull();
        assertThat(jdbcTemplate.queryForMap("select vt_state, vt_requested_at, vt_report_id from link_inspection where url_id = ?", LINK))
                .containsEntry("VT_STATE", "PENDING")
                .containsEntry("VT_REQUESTED_AT", java.sql.Timestamp.valueOf(NOW_UTC.plusSeconds(60)))
                .containsEntry("VT_REPORT_ID", REPORT_ID);
    }

    @Test
    void submitAtTheLimitAnswers409() {
        appProperties.getInspection().setVirusTotalDailyLimit(1);
        rateLimiter.increment("virustotal:2026-11-03", Duration.ofDays(2));

        assertThatThrownBy(() -> linkInspectionService.submitToVirusTotal(LINK, ADMIN))
                .isInstanceOf(VirusTotalUnavailableException.class)
                .hasMessage(USED_UP);

        verifyNoVirusTotalCall();
        verify(auditLogService).logFailure(ADMIN, AuditAction.SUBMIT_TO_VIRUSTOTAL, "Url", String.valueOf(LINK), SUBJECT + ", " + USED_UP);
        assertThat(rows(LINK)).isZero();
    }

    @Test
    void aSubmitVirusTotalRefusesChangesNothing() {
        when(virusTotalClient.submit(LONG_URL)).thenReturn(VirusTotalReport.failed("VirusTotal refused the API key."))
                .thenReturn(VirusTotalReport.rateLimited());

        assertThatThrownBy(() -> linkInspectionService.submitToVirusTotal(LINK, ADMIN))
                .isInstanceOf(VirusTotalUnavailableException.class)
                .hasMessage("VirusTotal refused the API key.");
        assertThatThrownBy(() -> linkInspectionService.submitToVirusTotal(LINK, ADMIN))
                .isInstanceOf(ServiceBusyException.class)
                .hasMessage("VirusTotal takes 4 requests a minute. Try again in a minute.");

        assertThat(rows(LINK)).isZero();
        verify(auditLogService, times(2)).logFailure(anyString(), any(), anyString(), anyString(), anyString());
        verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void submitForAnUnknownLinkIsNotFound() {
        assertThatThrownBy(() -> linkInspectionService.submitToVirusTotal(9999L, ADMIN))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessage("Link not found.");
        verifyNoVirusTotalCall();
    }

    @Test
    void theReportLinkPointsAtTheReportOrASearch() {
        verdict(LINK, "DONE", REPORT_ID, 0);
        jdbcTemplate.update("insert into link_inspection (url_id, vt_state, vt_requested_at, vt_checked_at, vt_url) values (?, 'NOT_FOUND', ?, ?, ?)",
                OTHER_LINK, NOW_UTC, NOW_UTC, "https://njuskaio.cam/a/MAUB3?x=1&y=2");

        assertThat(linkInspectionService.inspection(LINK).virusTotal().reportUrl()).isEqualTo("https://www.virustotal.com/gui/url/" + REPORT_ID);
        assertThat(linkInspectionService.inspection(OTHER_LINK).virusTotal())
                .extracting(VirusTotalView::state, VirusTotalView::checkedUrl, VirusTotalView::reportUrl)
                .containsExactly(InspectionState.NOT_FOUND, "https://njuskaio.cam/a/MAUB3?x=1&y=2",
                        "https://www.virustotal.com/gui/search/https%3A%2F%2Fnjuskaio.cam%2Fa%2FMAUB3%3Fx%3D1%26y%3D2");
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

        assertThat(screenshotRequests(LINK)).isZero();
        assertThatThrownBy(() -> linkInspectionService.takeScreenshot(LINK))
                .isInstanceOf(ScreenshotsUnavailableException.class)
                .hasMessage("Screenshots aren't set up.");
        assertThat(linkInspectionService.inspection(LINK).screenshot().configured()).isFalse();

        appProperties.getCloudflare().setBrowserApiToken("token-1");
        appProperties.getCloudflare().setAccountId(" ");
        linkInspectionService.requestScreenshot(LINK);
        assertThat(screenshotRequests(LINK)).isZero();
    }

    @Test
    void settingOffNothingIsQueued() {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.LINK_SCREENSHOTS);

        linkInspectionService.requestScreenshot(LINK);
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new LinkReviewOpened(LINK)));

        assertThat(screenshotRequests(LINK)).isZero();
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
        final var service = serviceOver(inspections, screenshots, mock(UrlRepository.class));

        service.dropFor(LINK);

        final var order = inOrder(inspections, screenshots);
        order.verify(inspections).deleteByUrlId(LINK);
        order.verify(screenshots).deleteByUrlId(LINK);
    }

    @Test
    void aQueueRequestThatRacesAnotherInsertIsRetriedOnce() {
        final var inspections = mock(LinkInspectionRepository.class);
        final var urls = mock(UrlRepository.class);
        when(urls.existsById(LINK)).thenReturn(true);
        when(inspections.findById(LINK)).thenReturn(Optional.empty());
        when(inspections.saveAndFlush(any(LinkInspection.class))).thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        final var service = serviceOver(inspections, mock(LinkScreenshotRepository.class), urls);

        service.requestScreenshot(LINK);

        verify(inspections, times(2)).saveAndFlush(any(LinkInspection.class));
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
        assertThat(nothingYet.virusTotal()).isEqualTo(VirusTotalView.none(true));

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
        verdict(LINK, "DONE", REPORT_ID, 4);
        final var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        final var summaries = linkInspectionService.summaries(List.of(LINK, OTHER_LINK, THIRD_LINK));

        assertThat(statistics.getPrepareStatementCount()).isOne();
        assertThat(statistics.getEntityStatistics(LinkScreenshot.class.getName()).getLoadCount()).isZero();
        assertThat(summaries).containsOnlyKeys(LINK, OTHER_LINK, THIRD_LINK);
        assertThat(summaries.get(LINK).screenshot().state()).isEqualTo(InspectionState.DONE);
        assertThat(summaries.get(LINK).virusTotal().state()).isEqualTo(InspectionState.DONE);
        assertThat(summaries.get(LINK).virusTotal().malicious()).isEqualTo(4);
        assertThat(summaries.get(OTHER_LINK).screenshot()).isEqualTo(new ScreenshotView(InspectionState.FAILED, NOW_UTC.minusMinutes(3), null, null, null,
                null, "The page didn't load within 20 seconds.", true));
        assertThat(summaries.get(OTHER_LINK).virusTotal()).isEqualTo(VirusTotalView.none(true));
        assertThat(summaries.get(THIRD_LINK).screenshot()).isEqualTo(new ScreenshotView(null, null, null, null, null, null, null, true));
        assertThat(summaries.get(THIRD_LINK).virusTotal()).isEqualTo(VirusTotalView.none(true));
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

    private void verdict(final long urlId, final String state, final String reportId, final int malicious) {
        if (rows(urlId) == 0) {
            jdbcTemplate.update("insert into link_inspection (url_id) values (?)", urlId);
        }
        jdbcTemplate.update("update link_inspection set vt_state = ?, vt_requested_at = ?, vt_checked_at = ?, vt_url = ?, vt_report_id = ?, "
                + "vt_malicious = ?, vt_suspicious = 0, vt_harmless = 60, vt_undetected = 20, vt_analysed_at = ? where url_id = ?", state,
                NOW_UTC.minusHours(1), NOW_UTC.minusHours(1), LONG_URL, reportId, malicious, NOW_UTC.minusDays(1), urlId);
    }

    private void verifyNoVirusTotalCall() {
        verify(virusTotalClient, never()).submit(anyString());
        verify(virusTotalClient, never()).lookup(anyString());
    }

    private DefaultLinkInspectionService serviceOver(final LinkInspectionRepository inspections, final LinkScreenshotRepository screenshots,
                                                     final UrlRepository urls) {
        return new DefaultLinkInspectionService(inspections, screenshots, urls, settingsService, mock(PlatformTransactionManager.class), clock,
                virusTotalClient, rateLimiter, appProperties, auditLogService, auditSubjects);
    }

    private void screenshot(final long urlId, final byte[] image) {
        jdbcTemplate.update("insert into link_screenshot (url_id, image, content_type) values (?, ?, 'image/jpeg')", urlId, image);
    }

    private int screenshotRequests(final long urlId) {
        return jdbcTemplate.queryForObject("select count(*) from link_inspection where url_id = ? and screenshot_state is not null", Integer.class, urlId);
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
