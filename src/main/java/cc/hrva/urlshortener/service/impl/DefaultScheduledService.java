package cc.hrva.urlshortener.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.service.ApiKeyService;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.ClickSummaryService;
import cc.hrva.urlshortener.service.DestinationCheckService;
import cc.hrva.urlshortener.service.LinkDigestService;
import cc.hrva.urlshortener.service.ResetTokenService;
import cc.hrva.urlshortener.service.SafeBrowsingRecheckService;
import cc.hrva.urlshortener.service.NotificationDeliveryService;
import cc.hrva.urlshortener.service.ScheduledService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.UrlNotificationService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.WeeklyDigestService;
import cc.hrva.urlshortener.service.domain.CustomDomainLifecycle;
import cc.hrva.urlshortener.service.domain.CustomDomainReconciler;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class DefaultScheduledService implements ScheduledService {

    private final UrlService urlService;
    private final UserService userService;
    private final ApiKeyService apiKeyService;
    private final ResetTokenService resetTokenService;
    private final UrlNotificationService urlNotificationService;
    private final SafeBrowsingRecheckService safeBrowsingRecheckService;
    private final SendingEmailService sendingEmailService;
    private final LinkDigestService linkDigestService;
    private final BlocklistSourceService blocklistSourceService;
    private final WeeklyDigestService weeklyDigestService;
    private final DestinationCheckService destinationCheckService;
    private final NotificationDeliveryService notificationDeliveryService;
    private final ClickSummaryService clickSummaryService;
    private final CustomDomainLifecycle customDomainLifecycle;
    private final CustomDomainReconciler customDomainReconciler;
    private final CustomDomainRegistry customDomainRegistry;
    private final BioHandleHoldRepository bioHandleHoldRepository;
    private final EmailLogRepository emailLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final AppProperties appProperties;
    private final Clock clock;

    @Override
    @Scheduled(cron = "0 */1 * * * *")
    public void deactivateExpiredUrls() {
        log.info("Scheduled cleanup: expired URLs");
        urlService.deactivateExpiredUrls();
    }

    @Override
    @Scheduled(cron = "0 0 * * * *")
    public void deactivateExpiredApiKeys() {
        log.info("Scheduled cleanup: expired API keys");
        apiKeyService.deactivateExpired();
    }

    @Override
    @Scheduled(cron = "0 0 10 * * *")
    public void deactivateUnusedUserAccounts() {
        log.info("Scheduled cleanup: unused user accounts");
        userService.deactivateUnusedUserAccounts();
    }

    @Override
    @Scheduled(cron = "0 */1 * * * *")
    public void cleanupExpiredPasswordResetTokens() {
        log.info("Scheduled cleanup: password reset tokens");
        resetTokenService.deactivateExpiredPasswordResetTokens();
        resetTokenService.deleteExpiredPasswordResetTokens();
    }

    @Override
    @Scheduled(cron = "0 0 8 * * *")
    public void notifyExpiringUrls() {
        log.info("Scheduled: expiring URL notifications");
        urlNotificationService.notifyExpiringUrls();
    }

    @Override
    @Scheduled(cron = "0 30 4 * * *")
    public void recheckUrlsSafeBrowsing() {
        log.info("Scheduled: SafeBrowsing recheck");
        safeBrowsingRecheckService.recheckActiveUrls();
    }

    @Override
    @Scheduled(cron = "0 */5 * * * *")
    public void failInterruptedEmails() {
        log.info("Scheduled cleanup: interrupted emails");
        sendingEmailService.failInterruptedEmails();
    }

    @Override
    @Scheduled(cron = "0 15 3 * * *")
    public void forgetOldEmailContent() {
        log.info("Scheduled cleanup: old email content");
        sendingEmailService.forgetOldEmailContent();
    }

    @Override
    @Scheduled(cron = "0 0 9 * * *")
    public void sendLinkDigest() {
        log.info("Scheduled: link digest");
        linkDigestService.sendDailyDigest();
    }

    @Override
    @Scheduled(cron = "0 0 * * * *")
    public void refreshDueBlocklists() {
        log.info("Scheduled: blocklist refresh");
        blocklistSourceService.refreshDue();
    }

    @Override
    @Scheduled(cron = "30 */5 * * * *")
    public void reloadMissingBlocklists() {
        log.info("Scheduled: missing blocklists");
        blocklistSourceService.reloadMissing();
    }

    @Override
    @Scheduled(cron = "0 0 8 * * MON", zone = "${app.analytics.zone}")
    public void sendWeeklyDigest() {
        log.info("Scheduled: weekly digest");
        weeklyDigestService.sendWeeklyDigest();
    }

    @Override
    @Scheduled(cron = "0 45 3 * * *")
    public void releaseExpiredBioHandles() {
        final var released = bioHandleHoldRepository.deleteByHeldUntilBefore(LocalDateTime.now(clock));
        log.info("Scheduled cleanup: expired bio handles released={}", released);
    }

    @Override
    @Scheduled(cron = "0 50 3 * * *")
    public void deleteOldLogs() {
        final var now = LocalDateTime.now(clock);
        final var retention = appProperties.getRetention();
        final var emailLogsDeleted = emailLogRepository.deleteByCreatedAtBefore(now.minusDays(retention.getEmailLogDays()));
        final var auditLogsDeleted = auditLogRepository.deleteByPerformedAtBefore(now.minusMonths(retention.getAuditLogMonths()));
        log.info("Scheduled cleanup: old logs emailLogsDeleted={} auditLogsDeleted={}", emailLogsDeleted, auditLogsDeleted);
    }

    @Override
    @Scheduled(cron = "0 0 2 * * *", zone = "${app.analytics.zone}")
    public void checkDestinations() {
        log.info("Scheduled: destination check");
        destinationCheckService.runNightly();
    }

    @Override
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void drainNotificationDeliveries() {
        notificationDeliveryService.drainInBackground();
    }

    @Override
    @Scheduled(cron = "0 55 3 * * *")
    public void deleteOldNotificationRecords() {
        notificationDeliveryService.deleteOld();
    }

    @Override
    @Scheduled(cron = "0 15 1 * * *", zone = "${app.analytics.zone}")
    public void sendDailyClickSummaries() {
        log.info("Scheduled: daily click summaries");
        clickSummaryService.sendDailyInBackground();
    }

    @Override
    @Scheduled(cron = "0 0 9 * * MON", zone = "${app.analytics.zone}")
    public void sendWeeklyClickSummaries() {
        log.info("Scheduled: weekly click summaries");
        clickSummaryService.sendWeeklyInBackground();
    }

    @Override
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void runDomainLifecycle() {
        customDomainLifecycle.runInBackground();
    }

    @Override
    @Scheduled(cron = "0 40 3 * * *", zone = "${app.analytics.zone}")
    public void reconcileCustomDomains() {
        log.info("Scheduled: custom domain reconcile");
        customDomainReconciler.reconcileInBackground();
    }

    @Override
    @Scheduled(fixedDelay = 60_000)
    public void reloadCustomDomainRoutes() {
        customDomainRegistry.reload();
    }

}
