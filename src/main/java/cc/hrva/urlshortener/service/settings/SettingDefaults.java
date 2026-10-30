package cc.hrva.urlshortener.service.settings;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.security.OAuthProviders;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class SettingDefaults {

    private static final String GOOGLE_NOT_CONFIGURED = "Google sign-in isn't configured.";
    private static final String GITHUB_NOT_CONFIGURED = "GitHub sign-in isn't configured.";
    private static final String TURNSTILE_NOT_CONFIGURED = "Turnstile keys aren't set, so no check runs.";

    private final Map<SettingKey, Supplier<Object>> defaults = new EnumMap<>(SettingKey.class);
    private final Map<SettingKey, Supplier<Optional<String>>> availability = new EnumMap<>(SettingKey.class);

    public SettingDefaults(final AppProperties appProperties, final OAuthProviders oAuthProviders) {
        final var moderation = appProperties.getModeration();
        final var emails = moderation.getEmails();
        final var campaign = appProperties.getCampaign();
        final var trafficAlerts = appProperties.getTrafficAlerts();
        final var weeklyDigest = appProperties.getDigest().getWeekly();
        final var abuseReports = appProperties.getAbuseReports();
        register(SettingKey.ANONYMOUS_LINKS, appProperties::isAnonymousUrlCreationEnabled);
        register(SettingKey.NEW_SIGNUPS, appProperties::isRegistrationEnabled);
        register(SettingKey.ANONYMOUS_LINKS_PER_HOUR, moderation::getAnonymousLinksPerHour);
        register(SettingKey.CUSTOM_ALIASES, moderation::isCustomAliases);
        register(SettingKey.SAFE_BROWSING_CHECK, moderation::isSafeBrowsingCheck);
        register(SettingKey.SAFE_BROWSING_FAIL_CLOSED, appProperties::isSafeBrowsingFailClosed);
        register(SettingKey.BLOCKLIST_CHECK, moderation::isBlocklistCheck);
        register(SettingKey.SUSPICIOUS_LINK_CHECK, moderation::isSuspiciousLinkCheck);
        register(SettingKey.NIGHTLY_SAFE_BROWSING, moderation::isNightlySafeBrowsing);
        register(SettingKey.NIGHTLY_DESTINATION_CHECK, () -> appProperties.getDestinationCheck().isEnabled());
        register(SettingKey.SAME_SHAPE_ALERT, campaign::isEnabled);
        register(SettingKey.CAMPAIGN_LINKS, campaign::getLinks);
        register(SettingKey.CAMPAIGN_DOMAINS, campaign::getDomains);
        register(SettingKey.CAMPAIGN_WINDOW_HOURS, () -> Math.clamp(campaign.getWindow().toHours(), 1, 168));
        register(SettingKey.TRAFFIC_ALERTS, trafficAlerts::isEnabled);
        register(SettingKey.TRAFFIC_LINK_CLICKS, trafficAlerts::getLinkClicks);
        register(SettingKey.TRAFFIC_SHARE_PERCENT, trafficAlerts::getSharePercent);
        register(SettingKey.TRAFFIC_MIN_ANONYMOUS_CLICKS, trafficAlerts::getMinAnonymousClicks);
        register(SettingKey.BURST_LINKS, () -> appProperties.getBurst().getLinks());
        register(SettingKey.WEEKLY_DIGEST, weeklyDigest::isEnabled);
        register(SettingKey.WEEKLY_DIGEST_EMAIL, weeklyDigest::isEmail);
        register(SettingKey.SEND_EMAILS, moderation::isSendEmails);
        register(SettingKey.EMAIL_LINK_BLOCKED, emails::isLinkBlocked);
        register(SettingKey.EMAIL_MALWARE_FOUND, emails::isMalwareFound);
        register(SettingKey.EMAIL_DEAD_LINKS, emails::isDeadLinks);
        register(SettingKey.EMAIL_LINK_EXPIRING, emails::isLinkExpiring);
        register(SettingKey.EMAIL_WEEKLY_SUMMARY, emails::isWeeklySummary);
        register(SettingKey.EMAIL_ADMIN_DAILY, emails::isAdminDaily);
        register(SettingKey.EMAIL_NEW_USER_NOTICE, emails::isNewUserNotice);
        register(SettingKey.EMAIL_CUSTOM_DOMAIN, emails::isCustomDomain);
        register(SettingKey.CAMPAIGN_HOLD, moderation::isCampaignHold);
        register(SettingKey.CAMPAIGN_HOLD_DAYS, moderation::getCampaignHoldDays);
        register(SettingKey.ABUSE_REPORTER_NAME, abuseReports::getReporterName);
        register(SettingKey.ABUSE_REPORTER_ADDRESS, () -> List.of(abuseReports.getReporterAddress()));
        register(SettingKey.ABUSE_CC, () -> List.copyOf(abuseReports.getCc()));
        register(SettingKey.ABUSE_RECHECK_DAYS, abuseReports::getRecheckDays);
        register(SettingKey.GOOGLE_SIGN_IN, moderation::isGoogleSignIn);
        register(SettingKey.GITHUB_SIGN_IN, moderation::isGithubSignIn);
        register(SettingKey.RANDOM_DOMAIN_REVIEW, moderation::isRandomDomainReview);
        register(SettingKey.RANDOM_DOMAIN_MAX_AGE_DAYS, moderation::getRandomDomainMaxAgeDays);
        register(SettingKey.WARNING_PAGE, moderation::isWarningPage);
        register(SettingKey.WARNING_PAGE_MAX_AGE_DAYS, moderation::getWarningPageMaxAgeDays);
        register(SettingKey.HUMAN_CHECK, moderation::isHumanCheck);
        register(SettingKey.ANONYMOUS_EXPIRY_DAYS, moderation::getAnonymousExpiryDays);
        register(SettingKey.ANONYMOUS_VISIT_LIMIT, moderation::getAnonymousVisitLimit);
        registerAvailability(SettingKey.GOOGLE_SIGN_IN,
                () -> unavailableUnless(oAuthProviders.configured(OAuthProviders.GOOGLE), GOOGLE_NOT_CONFIGURED));
        registerAvailability(SettingKey.GITHUB_SIGN_IN,
                () -> unavailableUnless(oAuthProviders.configured(OAuthProviders.GITHUB), GITHUB_NOT_CONFIGURED));
        registerAvailability(SettingKey.HUMAN_CHECK,
                () -> unavailableUnless(turnstileConfigured(appProperties.getTurnstile()), TURNSTILE_NOT_CONFIGURED));
    }

    public Object defaultOf(final SettingKey key) {
        final var supplier = defaults.get(key);
        if (supplier == null) {
            throw new IllegalStateException("No default for setting " + key);
        }
        return supplier.get();
    }

    public Optional<String> unavailableReason(final SettingKey key) {
        return availability.getOrDefault(key, Optional::empty).get();
    }

    private void register(final SettingKey key, final Supplier<Object> defaultValue) {
        defaults.put(key, defaultValue);
    }

    private void registerAvailability(final SettingKey key, final Supplier<Optional<String>> unavailableReason) {
        availability.put(key, unavailableReason);
    }

    private static boolean turnstileConfigured(final AppProperties.Turnstile turnstile) {
        return StringUtils.isNotBlank(turnstile.getSiteKey()) && StringUtils.isNotBlank(turnstile.getSecretKey());
    }

    private static Optional<String> unavailableUnless(final boolean available, final String reason) {
        return available ? Optional.empty() : Optional.of(reason);
    }

}
