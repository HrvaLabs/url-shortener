package cc.hrva.urlshortener.service.jobs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.Set;

public final class JobLabels {

    private static final SequencedMap<String, String> LABELS = labels();
    private static final Set<String> INTERNAL = Set.of(
            "DefaultSettingsService.refresh",
            "DefaultClickCheckService.reload",
            "DefaultScheduledService.reloadMissingBlocklists",
            "DefaultScheduledService.reloadCustomDomainRoutes",
            "InspectionGauges.refreshInspectionGauges");

    private JobLabels() {
    }

    public static SequencedMap<String, String> all() {
        return LABELS;
    }

    public static String labelOf(final String name) {
        return LABELS.getOrDefault(name, name);
    }

    public static boolean isInternal(final String name) {
        return INTERNAL.contains(name);
    }

    private static SequencedMap<String, String> labels() {
        final var labels = new LinkedHashMap<String, String>();
        labels.put("DefaultScheduledService.deactivateExpiredUrls", "Turn off expired links");
        labels.put("DefaultScheduledService.cleanupExpiredPasswordResetTokens", "Delete expired password reset tokens");
        labels.put("DefaultScheduledService.deactivateExpiredApiKeys", "Turn off expired API keys");
        labels.put("DefaultScheduledService.failInterruptedEmails", "Mark interrupted emails as failed");
        labels.put("DefaultScheduledService.drainNotificationDeliveries", "Send due notifications");
        labels.put("InspectionWorker.takeNextScreenshot", "Take the next screenshot");
        labels.put("InspectionWorker.lookUpNextOnVirusTotal", "Look up the next link on VirusTotal");
        labels.put("DefaultScheduledService.refreshDueBlocklists", "Refresh due blocklists");
        labels.put("DefaultScheduledService.reloadMissingBlocklists", "Reload missing blocklists");
        labels.put("DefaultClickCheckService.reload", "Reload the click-time blocklists");
        labels.put("DefaultScheduledService.checkTraffic", "Check traffic for alerts");
        labels.put("DefaultDiscordAlertService.flush", "Post Discord alerts");
        labels.put("DefaultSettingsService.refresh", "Reload the settings");
        labels.put("DefaultScheduledService.runDomainLifecycle", "Set up and check custom domains");
        labels.put("DefaultScheduledService.reloadCustomDomainRoutes", "Reload custom domain routes");
        labels.put("InspectionGauges.refreshInspectionGauges", "Read the inspection numbers for Prometheus");
        labels.put("DefaultScheduledService.checkDestinations", "Check link destinations");
        labels.put("DefaultScheduledService.forgetOldEmailContent", "Delete the content of old emails");
        labels.put("DcrClientCleanupJob.deleteStaleAuthorizationsAndClients", "Delete expired MCP sign-ins and unused MCP apps");
        labels.put("InspectionWorker.expireOldScreenshots", "Delete old screenshots");
        labels.put("DefaultScheduledService.reconcileCustomDomains", "Compare custom domains with Cloudflare");
        labels.put("DefaultScheduledService.releaseExpiredBioHandles", "Release expired bio page handles");
        labels.put("DefaultScheduledService.deleteOldLogs", "Delete old email and audit logs");
        labels.put("DefaultScheduledService.deleteOldNotificationRecords", "Delete old notification records");
        labels.put("DefaultScheduledService.recheckUrlsSafeBrowsing", "Scan active links overnight");
        labels.put("DefaultScheduledService.recheckReportedDomains", "Check reported domains again");
        labels.put("DefaultScheduledService.notifyExpiringUrls", "Send link expiry reminders");
        labels.put("DefaultScheduledService.sendDailyClickSummaries", "Send daily click summaries");
        labels.put("DefaultScheduledService.sendLinkDigest", "Send the decisions email");
        labels.put("DefaultScheduledService.deactivateUnusedUserAccounts", "Warn and turn off unused accounts");
        labels.put("DefaultScheduledService.sendWeeklyDigest", "Send the weekly digest");
        labels.put("DefaultScheduledService.sendWeeklyClickSummaries", "Send weekly click summaries");
        return Collections.unmodifiableSequencedMap(labels);
    }

}
