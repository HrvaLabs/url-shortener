package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.NotificationType;

public final class NotificationDescriptions {

    private NotificationDescriptions() {
    }

    public static String of(final NotificationType type) {
        return switch (type) {
            case DEAD_LINK_EMAIL -> "Email me when one of my links stops working. Links are checked every night.";
            case WEEKLY_SUMMARY_EMAIL -> "A summary of last week's clicks every Monday morning.";
            case EXPIRY_REMINDER_EMAIL -> "Email me a day before one of my links expires.";
            case LINK_DEAD, LINK_BACK, DAILY_SUMMARY, WEEKLY_SUMMARY -> label(type);
        };
    }

    public static String label(final NotificationType type) {
        return switch (type) {
            case DEAD_LINK_EMAIL -> "Dead links";
            case WEEKLY_SUMMARY_EMAIL, WEEKLY_SUMMARY -> "Weekly summary";
            case EXPIRY_REMINDER_EMAIL -> "Expiry reminders";
            case LINK_DEAD -> "A link stopped working";
            case LINK_BACK -> "A link works again";
            case DAILY_SUMMARY -> "Daily click summary";
        };
    }

    public static String label(final ChannelType type) {
        return switch (type) {
            case DISCORD -> "Discord";
            case SLACK -> "Slack";
            case TELEGRAM -> "Telegram";
            case NTFY -> "ntfy";
            case WEBHOOK -> "Webhook";
        };
    }

    public static String channelLabel(final ChannelType type, final String name) {
        return label(type) + ": " + name;
    }

}
