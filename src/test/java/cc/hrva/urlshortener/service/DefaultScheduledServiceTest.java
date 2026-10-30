package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.service.domain.CustomDomainLifecycle;
import cc.hrva.urlshortener.service.domain.CustomDomainReconciler;
import cc.hrva.urlshortener.service.impl.DefaultClickSummaryService;
import cc.hrva.urlshortener.service.impl.DefaultDestinationCheckService;
import cc.hrva.urlshortener.service.impl.DefaultNotificationDeliveryService;
import cc.hrva.urlshortener.service.impl.DefaultScheduledService;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultScheduledServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T01:45:00Z");

    private ScheduledService scheduledService;

    @Mock
    private UrlService urlService;

    @Mock
    private UserService userService;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private ResetTokenService resetTokenService;

    @Mock
    private UrlNotificationService urlNotificationService;

    @Mock
    private SafeBrowsingRecheckService safeBrowsingRecheckService;

    @Mock
    private SendingEmailService sendingEmailService;

    @Mock
    private LinkDigestService linkDigestService;

    @Mock
    private BlocklistSourceService blocklistSourceService;

    @Mock
    private WeeklyDigestService weeklyDigestService;

    @Mock
    private DestinationCheckService destinationCheckService;

    @Mock
    private NotificationDeliveryService notificationDeliveryService;

    @Mock
    private ClickSummaryService clickSummaryService;

    @Mock
    private CustomDomainLifecycle customDomainLifecycle;

    @Mock
    private CustomDomainReconciler customDomainReconciler;

    @Mock
    private BioHandleHoldRepository bioHandleHoldRepository;

    @Mock
    private EmailLogRepository emailLogRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private final AppProperties appProperties = new AppProperties();

    @BeforeEach
    void setUp() {
        this.scheduledService = new DefaultScheduledService(urlService, userService, apiKeyService, resetTokenService, urlNotificationService, safeBrowsingRecheckService, sendingEmailService, linkDigestService, blocklistSourceService,
                weeklyDigestService, destinationCheckService, notificationDeliveryService, clickSummaryService, customDomainLifecycle, customDomainReconciler, bioHandleHoldRepository, emailLogRepository, auditLogRepository, appProperties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void emailLogsOlderThanNinetyDaysAndAuditRowsOlderThanTwelveMonthsAreDeleted(final CapturedOutput output) {
        when(emailLogRepository.deleteByCreatedAtBefore(LocalDateTime.of(2026, 7, 28, 1, 45))).thenReturn(14);
        when(auditLogRepository.deleteByPerformedAtBefore(LocalDateTime.of(2025, 10, 26, 1, 45))).thenReturn(3);

        scheduledService.deleteOldLogs();

        verify(emailLogRepository).deleteByCreatedAtBefore(LocalDateTime.of(2026, 7, 28, 1, 45));
        verify(auditLogRepository).deleteByPerformedAtBefore(LocalDateTime.of(2025, 10, 26, 1, 45));
        assertThat(output).contains("Scheduled cleanup: old logs emailLogsDeleted=14 auditLogsDeleted=3");
    }

    @Test
    void theConfiguredRetentionPeriodsSetTheCutoffs() {
        appProperties.getRetention().setEmailLogDays(30);
        appProperties.getRetention().setAuditLogMonths(3);

        scheduledService.deleteOldLogs();

        verify(emailLogRepository).deleteByCreatedAtBefore(LocalDateTime.of(2026, 9, 26, 1, 45));
        verify(auditLogRepository).deleteByPerformedAtBefore(LocalDateTime.of(2026, 7, 26, 1, 45));
    }

    @Test
    void oldLogsAreDeletedDailyAtTenToFour() throws NoSuchMethodException {
        final var scheduled = DefaultScheduledService.class.getMethod("deleteOldLogs").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 50 3 * * *");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void releasesExpiredBioHandles(final CapturedOutput output) {
        when(bioHandleHoldRepository.deleteByHeldUntilBefore(LocalDateTime.of(2026, 10, 26, 1, 45))).thenReturn(3L);

        scheduledService.releaseExpiredBioHandles();

        verify(bioHandleHoldRepository).deleteByHeldUntilBefore(LocalDateTime.of(2026, 10, 26, 1, 45));
        assertThat(output).contains("Scheduled cleanup: expired bio handles released=3");
    }

    @Test
    void expiredBioHandlesAreReleasedDailyAtQuarterToFour() throws NoSuchMethodException {
        final var scheduled = DefaultScheduledService.class.getMethod("releaseExpiredBioHandles").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 45 3 * * *");
    }

    @Test
    void shouldSendWeeklyDigest() {
        scheduledService.sendWeeklyDigest();
        verify(weeklyDigestService).sendWeeklyDigest();
    }

    @Test
    void theWeeklyDigestRunsMondaysAtEightInTheAnalyticsZone() throws NoSuchMethodException {
        final var scheduled = DefaultScheduledService.class.getMethod("sendWeeklyDigest").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 8 * * MON");
        assertThat(scheduled.zone()).isEqualTo("${app.analytics.zone}");
    }

    @Test
    void theWeeklyDigestFiresAtEightInZagrebOnEitherSideOfTheClockChange() throws NoSuchMethodException, IOException {
        final var scheduled = DefaultScheduledService.class.getMethod("sendWeeklyDigest").getAnnotation(Scheduled.class);
        final var sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).forEach(sources::addLast);
        final var zone = ZoneId.of(new PropertySourcesPropertyResolver(sources).resolveRequiredPlaceholders(scheduled.zone()));
        final var cron = CronExpression.parse(scheduled.cron());

        assertThat(zone).isEqualTo(ZoneId.of("Europe/Zagreb"));
        assertThat(nextRun(cron, "2026-10-25T12:00:00Z", zone)).isEqualTo("2026-10-26T07:00:00Z");
        assertThat(nextRun(cron, "2026-10-26T07:00:00Z", zone)).isEqualTo("2026-11-02T07:00:00Z");
        assertThat(nextRun(cron, "2026-03-29T12:00:00Z", zone)).isEqualTo("2026-03-30T06:00:00Z");
        assertThat(nextRun(cron, "2026-03-27T12:00:00Z", zone)).isEqualTo("2026-03-30T06:00:00Z");
    }

    private static Instant nextRun(final CronExpression cron, final String after, final ZoneId zone) {
        return cron.next(ZonedDateTime.ofInstant(Instant.parse(after), zone)).toInstant();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void checkDestinationsHandsTheNightlyCheckOff(final CapturedOutput output) {
        scheduledService.checkDestinations();

        verify(destinationCheckService).runNightly();
        verifyNoMoreInteractions(destinationCheckService);
        assertThat(output).contains("Scheduled: destination check");
    }

    @Test
    void theDestinationCheckRunsAtTwoInTheAnalyticsZone() throws NoSuchMethodException, IOException {
        final var scheduled = DefaultScheduledService.class.getMethod("checkDestinations").getAnnotation(Scheduled.class);
        final var sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).forEach(sources::addLast);
        final var zone = ZoneId.of(new PropertySourcesPropertyResolver(sources).resolveRequiredPlaceholders(scheduled.zone()));

        assertThat(scheduled.cron()).isEqualTo("0 0 2 * * *");
        assertThat(scheduled.zone()).isEqualTo("${app.analytics.zone}");
        assertThat(nextRun(CronExpression.parse(scheduled.cron()), "2026-10-26T12:00:00Z", zone)).isEqualTo("2026-10-27T01:00:00Z");
        assertThat(nextRun(CronExpression.parse(scheduled.cron()), "2026-07-01T12:00:00Z", zone)).isEqualTo("2026-07-02T00:00:00Z");
    }

    @Test
    void longJobsRunAsync() throws NoSuchMethodException {
        final var runNightly = DefaultDestinationCheckService.class.getMethod("runNightly");

        assertThat(runNightly.getAnnotation(Async.class)).isNotNull();
        assertThat(runNightly.getAnnotation(Async.class).value()).isEqualTo(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR);
        assertThat(DefaultScheduledService.class.getMethod("checkDestinations").getAnnotation(Async.class)).isNull();
        final var drainInBackground = DefaultNotificationDeliveryService.class.getMethod("drainInBackground");
        assertThat(drainInBackground.getAnnotation(Async.class)).isNotNull();
        assertThat(drainInBackground.getAnnotation(Async.class).value()).isEqualTo(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR);
        assertThat(DefaultScheduledService.class.getMethod("drainNotificationDeliveries").getAnnotation(Async.class)).isNull();
        final var sendDailyInBackground = DefaultClickSummaryService.class.getMethod("sendDailyInBackground");
        assertThat(sendDailyInBackground.getAnnotation(Async.class)).isNotNull();
        assertThat(sendDailyInBackground.getAnnotation(Async.class).value()).isEqualTo(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR);
        assertThat(DefaultScheduledService.class.getMethod("sendDailyClickSummaries").getAnnotation(Async.class)).isNull();
        final var sendWeeklyInBackground = DefaultClickSummaryService.class.getMethod("sendWeeklyInBackground");
        assertThat(sendWeeklyInBackground.getAnnotation(Async.class)).isNotNull();
        assertThat(sendWeeklyInBackground.getAnnotation(Async.class).value()).isEqualTo(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR);
        assertThat(DefaultScheduledService.class.getMethod("sendWeeklyClickSummaries").getAnnotation(Async.class)).isNull();
        final var runInBackground = CustomDomainLifecycle.class.getMethod("runInBackground");
        assertThat(runInBackground.getAnnotation(Async.class)).isNotNull();
        assertThat(runInBackground.getAnnotation(Async.class).value()).isEqualTo(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR);
        assertThat(DefaultScheduledService.class.getMethod("runDomainLifecycle").getAnnotation(Async.class)).isNull();
        final var reconcileInBackground = CustomDomainReconciler.class.getMethod("reconcileInBackground");
        assertThat(reconcileInBackground.getAnnotation(Async.class)).isNotNull();
        assertThat(reconcileInBackground.getAnnotation(Async.class).value()).isEqualTo(AsyncExecutorConfiguration.NOTIFICATION_TASK_EXECUTOR);
        assertThat(DefaultScheduledService.class.getMethod("reconcileCustomDomains").getAnnotation(Async.class)).isNull();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void domainLifecycleRunsEveryMinuteAsync(final CapturedOutput output) throws NoSuchMethodException {
        final var scheduled = DefaultScheduledService.class.getMethod("runDomainLifecycle").getAnnotation(Scheduled.class);

        scheduledService.runDomainLifecycle();

        assertThat(scheduled.fixedDelay()).isEqualTo(60_000L);
        assertThat(scheduled.initialDelay()).isEqualTo(30_000L);
        assertThat(scheduled.cron()).isEmpty();
        verify(customDomainLifecycle).runInBackground();
        verify(customDomainLifecycle, never()).runOnce();
        assertThat(output).doesNotContain("Scheduled");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reconcileRunsAt0340InTheAnalyticsZone(final CapturedOutput output) throws NoSuchMethodException, IOException {
        final var scheduled = DefaultScheduledService.class.getMethod("reconcileCustomDomains").getAnnotation(Scheduled.class);
        final var sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).forEach(sources::addLast);
        final var zone = ZoneId.of(new PropertySourcesPropertyResolver(sources).resolveRequiredPlaceholders(scheduled.zone()));
        final var cron = CronExpression.parse(scheduled.cron());

        scheduledService.reconcileCustomDomains();

        assertThat(scheduled.cron()).isEqualTo("0 40 3 * * *");
        assertThat(scheduled.zone()).isEqualTo("${app.analytics.zone}");
        assertThat(nextRun(cron, "2026-10-26T12:00:00Z", zone)).isEqualTo("2026-10-27T02:40:00Z");
        assertThat(nextRun(cron, "2026-07-01T12:00:00Z", zone)).isEqualTo("2026-07-02T01:40:00Z");
        verify(customDomainReconciler).reconcileInBackground();
        verify(customDomainReconciler, never()).reconcile();
        assertThat(output).contains("Scheduled: custom domain reconcile");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theWeeklyClickSummaryHandsOffToTheBackground(final CapturedOutput output) {
        scheduledService.sendWeeklyClickSummaries();

        verify(clickSummaryService).sendWeeklyInBackground();
        verify(clickSummaryService, never()).sendWeekly();
        assertThat(output).contains("Scheduled: weekly click summaries");
    }

    @Test
    void theWeeklyClickSummaryRunsMondaysAtNineInZagrebOnEitherSideOfTheClockChange() throws NoSuchMethodException, IOException {
        final var scheduled = DefaultScheduledService.class.getMethod("sendWeeklyClickSummaries").getAnnotation(Scheduled.class);
        final var sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).forEach(sources::addLast);
        final var zone = ZoneId.of(new PropertySourcesPropertyResolver(sources).resolveRequiredPlaceholders(scheduled.zone()));
        final var cron = CronExpression.parse(scheduled.cron());

        assertThat(scheduled.cron()).isEqualTo("0 0 9 * * MON");
        assertThat(scheduled.zone()).isEqualTo("${app.analytics.zone}");
        assertThat(nextRun(cron, "2026-10-25T12:00:00Z", zone)).isEqualTo("2026-10-26T08:00:00Z");
        assertThat(nextRun(cron, "2026-03-27T12:00:00Z", zone)).isEqualTo("2026-03-30T07:00:00Z");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theDailyClickSummaryHandsOffToTheBackground(final CapturedOutput output) {
        scheduledService.sendDailyClickSummaries();

        verify(clickSummaryService).sendDailyInBackground();
        verify(clickSummaryService, never()).sendDaily();
        assertThat(output).contains("Scheduled: daily click summaries");
    }

    @Test
    void theDailyClickSummaryRunsAtQuarterPastOneInTheAnalyticsZone() throws NoSuchMethodException, IOException {
        final var scheduled = DefaultScheduledService.class.getMethod("sendDailyClickSummaries").getAnnotation(Scheduled.class);
        final var sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).forEach(sources::addLast);
        final var zone = ZoneId.of(new PropertySourcesPropertyResolver(sources).resolveRequiredPlaceholders(scheduled.zone()));

        assertThat(scheduled.cron()).isEqualTo("0 15 1 * * *");
        assertThat(scheduled.zone()).isEqualTo("${app.analytics.zone}");
        assertThat(nextRun(CronExpression.parse(scheduled.cron()), "2026-10-26T12:00:00Z", zone)).isEqualTo("2026-10-27T00:15:00Z");
        assertThat(nextRun(CronExpression.parse(scheduled.cron()), "2026-07-01T12:00:00Z", zone)).isEqualTo("2026-07-01T23:15:00Z");
    }

    @Test
    void theDeliveryDrainHandsOffToTheBackground() {
        scheduledService.drainNotificationDeliveries();

        verify(notificationDeliveryService).drainInBackground();
        verify(notificationDeliveryService, never()).drain();
    }

    @Test
    void theDeliveryDrainRunsAMinuteAfterTheLastOne() throws NoSuchMethodException {
        final var scheduled = DefaultScheduledService.class.getMethod("drainNotificationDeliveries").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelay()).isEqualTo(60_000L);
        assertThat(scheduled.initialDelay()).isEqualTo(60_000L);
        assertThat(scheduled.cron()).isEmpty();
    }

    @Test
    void oldNotificationRecordsAreDeletedDailyAtFiveToFour() throws NoSuchMethodException {
        final var scheduled = DefaultScheduledService.class.getMethod("deleteOldNotificationRecords").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 55 3 * * *");
        scheduledService.deleteOldNotificationRecords();
        verify(notificationDeliveryService).deleteOld();
    }

    @Test
    void shouldForgetOldEmailContent() {
        scheduledService.forgetOldEmailContent();
        verify(sendingEmailService).forgetOldEmailContent();
    }

    @Test
    void shouldSendLinkDigest() {
        scheduledService.sendLinkDigest();
        verify(linkDigestService).sendDailyDigest();
    }

    @Test
    void shouldRefreshDueBlocklists() {
        scheduledService.refreshDueBlocklists();
        verify(blocklistSourceService).refreshDue();
    }

    @Test
    void shouldReloadMissingBlocklists() {
        scheduledService.reloadMissingBlocklists();
        verify(blocklistSourceService).reloadMissing();
    }

    @Test
    void shouldFailInterruptedEmails() {
        scheduledService.failInterruptedEmails();
        verify(sendingEmailService).failInterruptedEmails();
    }

    @Test
    void shouldCleanupExpiredPasswordResetTokens() {
        scheduledService.cleanupExpiredPasswordResetTokens();
        verify(resetTokenService).deactivateExpiredPasswordResetTokens();
        verify(resetTokenService).deleteExpiredPasswordResetTokens();
    }

    @Test
    void shouldDeactivateUnusedUserAccounts() {
        scheduledService.deactivateUnusedUserAccounts();
        verify(userService).deactivateUnusedUserAccounts();
    }

    @Test
    void shouldDeactivateExpiredApiKeys() {
        scheduledService.deactivateExpiredApiKeys();
        verify(apiKeyService).deactivateExpired();
    }

    @Test
    void shouldDeactivateExpiredUrls() {
        scheduledService.deactivateExpiredUrls();
        verify(urlService).deactivateExpiredUrls();
    }

    @Test
    void shouldNotifyExpiringUrls() {
        scheduledService.notifyExpiringUrls();
        verify(urlNotificationService).notifyExpiringUrls();
    }

    @Test
    void shouldRecheckUrlsSafeBrowsing() {
        scheduledService.recheckUrlsSafeBrowsing();
        verify(safeBrowsingRecheckService).recheckActiveUrls();
    }

    @Test
    void theNightlyScanRunsAtHalfPastFourAfterTheFourOClockListRefresh() throws NoSuchMethodException {
        final var scheduled = DefaultScheduledService.class.getMethod("recheckUrlsSafeBrowsing").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 30 4 * * *");
    }
}
