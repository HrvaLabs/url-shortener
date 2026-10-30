package cc.hrva.urlshortener.service.settings;

import lombok.Getter;

@Getter
public enum SettingKey {

    ANONYMOUS_LINKS(SettingGroup.ACCESS, "Links without an account",
            "Visitors can make short links without signing in.", null, false),
    NEW_SIGNUPS(SettingGroup.ACCESS, "New sign-ups",
            "New accounts can be created, with email or a sign-in provider.", null, false),
    ANONYMOUS_LINKS_PER_HOUR(SettingGroup.ACCESS, "Links without an account per network per hour",
            "Counted over a fixed hour.", ANONYMOUS_LINKS, 1, 100, "links"),
    CUSTOM_ALIASES(SettingGroup.LINKS, "Custom aliases",
            "Signed-in users choose their own short link on the web, the API and MCP. Links on a custom domain keep their aliases.", null, false),
    SAFE_BROWSING_CHECK(SettingGroup.SAFETY, "Google Safe Browsing check on new links",
            "New links, links turned back on and changed destinations are checked with Google Safe Browsing.", null, true),
    SAFE_BROWSING_FAIL_CLOSED(SettingGroup.SAFETY, "Refuse links when Google Safe Browsing is down",
            "Off lets links through while Google Safe Browsing can't be reached.", SAFE_BROWSING_CHECK, false),
    BLOCKLIST_CHECK(SettingGroup.SAFETY, "Blocklist check on new links and clicks",
            "The enabled blocklists and Spamhaus, on new links, clicks, the nightly recheck, redirect hops, Allow-rule restores, custom-domain "
                    + "applications and the nightly custom-domain check. Domain rules always apply.", null, true),
    SUSPICIOUS_LINK_CHECK(SettingGroup.SAFETY, "Suspicious-link check",
            "Lookalike brands, risky endings, login lures and young domains, on new links, the nightly recheck, redirect hops, Allow-rule "
                    + "restores, custom-domain applications and the nightly custom-domain check.", null, true),
    NIGHTLY_SAFE_BROWSING(SettingGroup.SAFETY, "Nightly Safe Browsing recheck",
            "The Safe Browsing part of the 04:30 recheck. The same run's Safe Browsing lookups on redirect hops still happen, and the other rechecks "
                    + "follow their own switches.", null, true),
    NIGHTLY_DESTINATION_CHECK(SettingGroup.SAFETY, "Nightly destination check",
            "The 02:00 check that finds dead destinations.", null, true),
    SAME_SHAPE_ALERT(SettingGroup.ALERTS, "Same-shape alert",
            "A Discord alert and a Campaigns row when links without an account share one URL shape across several domains. Off also stops holds.",
            null, true),
    CAMPAIGN_LINKS(SettingGroup.ALERTS, "Same-shape alert: links",
            "Links without an account that share one URL shape.", SAME_SHAPE_ALERT, 2, 50, "links"),
    CAMPAIGN_DOMAINS(SettingGroup.ALERTS, "Same-shape alert: domains",
            "How many different domains those links must use.", SAME_SHAPE_ALERT, 2, 50, "domains"),
    CAMPAIGN_WINDOW_HOURS(SettingGroup.ALERTS, "Same-shape alert: window",
            "Links are counted over this many hours.", SAME_SHAPE_ALERT, 1, 168, "hours"),
    CAMPAIGN_HOLD(SettingGroup.ALERTS, "Hold links with a campaign URL shape",
            "Holds the links of a same-shape alert. Off still sends the alert and lists the campaign.", SAME_SHAPE_ALERT, true),
    CAMPAIGN_HOLD_DAYS(SettingGroup.ALERTS, "Campaign hold: days",
            "New matching links are held for this many days after the last match.", CAMPAIGN_HOLD, 1, 90, "days"),
    TRAFFIC_ALERTS(SettingGroup.ALERTS, "Traffic alerts",
            "Discord alerts about busy links without an account.", null, false),
    TRAFFIC_LINK_CLICKS(SettingGroup.ALERTS, "Traffic alerts: clicks per link per day",
            "One link without an account reaching this many clicks in a day.", TRAFFIC_ALERTS, 5, 100_000, "clicks"),
    TRAFFIC_SHARE_PERCENT(SettingGroup.ALERTS, "Traffic alerts: share of the day's clicks",
            "Links without an account taking more than this share of the day's clicks.", TRAFFIC_ALERTS, 10, 99, "%"),
    TRAFFIC_MIN_ANONYMOUS_CLICKS(SettingGroup.ALERTS, "Traffic alerts: minimum clicks",
            "The share alert needs at least this many clicks on links without an account.", TRAFFIC_ALERTS, 10, 100_000, "clicks"),
    BURST_LINKS(SettingGroup.ALERTS, "Phishing wave alert: links",
            "New links to one domain, from at least 2 accounts or networks, within the window set in configuration.", null, 2, 50, "links"),
    WEEKLY_DIGEST(SettingGroup.DIGEST, "Weekly digest",
            "Monday's summary in Discord.", null, false),
    WEEKLY_DIGEST_EMAIL(SettingGroup.DIGEST, "Weekly digest by email",
            "Monday's summary by email to the admin.", null, false),
    SEND_EMAILS(SettingGroup.EMAILS, "Send emails",
            "Every email except account emails (verification, password reset, passkey added, account turned off).", null, false),
    EMAIL_LINK_BLOCKED(SettingGroup.EMAILS, "Link blocked",
            "Tells owners their link was blocked.", SEND_EMAILS, false),
    EMAIL_MALWARE_FOUND(SettingGroup.EMAILS, "Malware found",
            "Tells owners Google Safe Browsing flagged a destination.", SEND_EMAILS, false),
    EMAIL_DEAD_LINKS(SettingGroup.EMAILS, "Dead destinations",
            "Owners who opted in hear when a destination stops working.", SEND_EMAILS, false),
    EMAIL_LINK_EXPIRING(SettingGroup.EMAILS, "Link expiring",
            "Owners who opted in hear before a link expires.", SEND_EMAILS, false),
    EMAIL_WEEKLY_SUMMARY(SettingGroup.EMAILS, "Weekly link summary",
            "Owners who opted in get their week's clicks.", SEND_EMAILS, false),
    EMAIL_ADMIN_DAILY(SettingGroup.EMAILS, "Admin daily email",
            "The 09:00 email about reviews and automatic blocks.", SEND_EMAILS, false),
    EMAIL_NEW_USER_NOTICE(SettingGroup.EMAILS, "New user notice",
            "Emails the admin when someone signs up.", SEND_EMAILS, false),
    EMAIL_CUSTOM_DOMAIN(SettingGroup.EMAILS, "Custom domain updates",
            "Tells owners when their domain is approved, rejected, turned off or removed.", SEND_EMAILS, false),
    ABUSE_REPORTER_NAME(SettingGroup.ABUSE_REPORTS, SettingType.TEXT, "Abuse reports: your name",
            "Signs the report emails.", 1, 100),
    ABUSE_REPORTER_ADDRESS(SettingGroup.ABUSE_REPORTS, SettingType.ADDRESSES, "Abuse reports: your address",
            "Under your name in the report emails.", 1, 1),
    ABUSE_CC(SettingGroup.ABUSE_REPORTS, SettingType.ADDRESSES, "Abuse reports: Cc",
            "Copied on every report email.", 0, 5),
    ABUSE_RECHECK_DAYS(SettingGroup.ABUSE_REPORTS, "Abuse reports: recheck for",
            "Reported domains are looked up every day for this many days.", null, 1, 90, "days");

    private final SettingGroup group;
    private final SettingType type;
    private final String label;
    private final String description;
    private final SettingKey parent;
    private final boolean safety;
    private final Integer min;
    private final Integer max;
    private final boolean nullable;
    private final String noneLabel;
    private final String unit;
    private final Integer minItems;
    private final Integer maxItems;
    private final Integer minLength;
    private final Integer maxLength;

    SettingKey(final SettingGroup group, final String label, final String description, final SettingKey parent, final boolean safety) {
        this(group, SettingType.SWITCH, label, description, parent, safety, null, null, false, null, null, null, null, null, null);
    }

    SettingKey(final SettingGroup group, final String label, final String description, final SettingKey parent, final int min, final int max,
            final String unit) {
        this(group, SettingType.NUMBER, label, description, parent, false, min, max, false, null, unit, null, null, null, null);
    }

    SettingKey(final SettingGroup group, final SettingType type, final String label, final String description, final int minimum, final int maximum) {
        this(group, type, label, description, null, false, null, null, false, null, null,
                type == SettingType.ADDRESSES ? minimum : null, type == SettingType.ADDRESSES ? maximum : null,
                type == SettingType.TEXT ? minimum : null, type == SettingType.TEXT ? maximum : null);
    }

    SettingKey(final SettingGroup group, final SettingType type, final String label, final String description, final SettingKey parent,
            final boolean safety, final Integer min, final Integer max, final boolean nullable, final String noneLabel, final String unit,
            final Integer minItems, final Integer maxItems, final Integer minLength, final Integer maxLength) {
        this.group = group;
        this.type = type;
        this.label = label;
        this.description = description;
        this.parent = parent;
        this.safety = safety;
        this.min = min;
        this.max = max;
        this.nullable = nullable;
        this.noneLabel = noneLabel;
        this.unit = unit;
        this.minItems = minItems;
        this.maxItems = maxItems;
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

}
