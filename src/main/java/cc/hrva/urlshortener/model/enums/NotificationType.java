package cc.hrva.urlshortener.model.enums;

import java.util.Arrays;
import java.util.List;

public enum NotificationType {

    DEAD_LINK_EMAIL(true, null),
    WEEKLY_SUMMARY_EMAIL(true, null),
    EXPIRY_REMINDER_EMAIL(true, null),
    LINK_DEAD(false, "link.dead"),
    LINK_BACK(false, "link.back"),
    DAILY_SUMMARY(false, "summary.daily"),
    WEEKLY_SUMMARY(false, "summary.weekly");

    public static final List<NotificationType> EMAIL_TYPES = Arrays.stream(values()).filter(NotificationType::isEmail).toList();
    public static final List<NotificationType> CHANNEL_EVENTS = Arrays.stream(values()).filter(type -> !type.isEmail()).toList();

    private final boolean email;
    private final String wireName;

    NotificationType(final boolean email, final String wireName) {
        this.email = email;
        this.wireName = wireName;
    }

    public boolean isEmail() {
        return email;
    }

    public String wireName() {
        return wireName;
    }

}
