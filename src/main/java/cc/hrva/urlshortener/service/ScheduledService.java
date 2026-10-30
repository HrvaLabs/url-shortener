package cc.hrva.urlshortener.service;

public interface ScheduledService {

    void deactivateExpiredUrls();
    void deactivateExpiredApiKeys();
    void deactivateUnusedUserAccounts();
    void cleanupExpiredPasswordResetTokens();
    void notifyExpiringUrls();
    void recheckUrlsSafeBrowsing();
    void failInterruptedEmails();
    void forgetOldEmailContent();
    void sendLinkDigest();
    void refreshDueBlocklists();
    void reloadMissingBlocklists();
    void sendWeeklyDigest();
    void releaseExpiredBioHandles();
    void deleteOldLogs();
    void checkDestinations();
    void drainNotificationDeliveries();
    void deleteOldNotificationRecords();
    void sendDailyClickSummaries();
    void sendWeeklyClickSummaries();
    void runDomainLifecycle();
    void reconcileCustomDomains();
    void reloadCustomDomainRoutes();
    void checkTraffic();
    void recheckReportedDomains();

}
