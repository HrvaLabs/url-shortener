package cc.hrva.urlshortener.service.jobs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

public final class JobLabels {

    private static final SequencedMap<String, String> LABELS = labels();

    private JobLabels() {
    }

    public static SequencedMap<String, String> all() {
        return LABELS;
    }

    public static String labelOf(final String name) {
        return LABELS.getOrDefault(name, name);
    }

    private static SequencedMap<String, String> labels() {
        final var labels = new LinkedHashMap<String, String>();
        labels.put("deactivateExpiredUrls", "Turn off expired links");
        labels.put("cleanupExpiredPasswordResetTokens", "Delete expired password reset tokens");
        labels.put("deactivateExpiredApiKeys", "Turn off expired API keys");
        labels.put("failInterruptedEmails", "Mark interrupted emails as failed");
        labels.put("drainNotificationDeliveries", "Send due notifications");
        labels.put("takeNextScreenshot", "Take the next screenshot");
        labels.put("lookUpNextOnVirusTotal", "Look up the next link on VirusTotal");
        labels.put("refreshDueBlocklists", "Refresh due blocklists");
        labels.put("reloadMissingBlocklists", "Reload missing blocklists");
        labels.put("reload", "Reload the click-time blocklists");
        labels.put("checkTraffic", "Check traffic for alerts");
        labels.put("flush", "Post Discord alerts");
        labels.put("refresh", "Reload the settings");
        labels.put("runDomainLifecycle", "Set up and check custom domains");
        labels.put("reloadCustomDomainRoutes", "Reload custom domain routes");
        labels.put("refreshInspectionGauges", "Read the inspection numbers for Prometheus");
        labels.put("checkDestinations", "Check link destinations");
        labels.put("forgetOldEmailContent", "Delete the content of old emails");
        labels.put("deleteStaleAuthorizationsAndClients", "Delete expired MCP sign-ins and unused MCP apps");
        labels.put("expireOldScreenshots", "Delete old screenshots");
        labels.put("reconcileCustomDomains", "Compare custom domains with Cloudflare");
        labels.put("releaseExpiredBioHandles", "Release expired bio page handles");
        labels.put("deleteOldLogs", "Delete old email and audit logs");
        labels.put("deleteOldNotificationRecords", "Delete old notification records");
        labels.put("recheckUrlsSafeBrowsing", "Scan active links overnight");
        labels.put("recheckReportedDomains", "Check reported domains again");
        labels.put("notifyExpiringUrls", "Send link expiry reminders");
        labels.put("sendDailyClickSummaries", "Send daily click summaries");
        labels.put("sendLinkDigest", "Send the decisions email");
        labels.put("deactivateUnusedUserAccounts", "Warn and turn off unused accounts");
        labels.put("sendWeeklyDigest", "Send the weekly digest");
        labels.put("sendWeeklyClickSummaries", "Send weekly click summaries");
        return Collections.unmodifiableSequencedMap(labels);
    }

}
