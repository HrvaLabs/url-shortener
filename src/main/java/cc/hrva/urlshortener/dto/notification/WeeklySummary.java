package cc.hrva.urlshortener.dto.notification;

import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import java.time.LocalDate;
import java.util.List;

public record WeeklySummary(long userId, String email, LocalDate from, LocalDate to, WeeklyDigest.Change clicks, long uniqueVisitors,
                            List<WeeklyDigest.TopLink> topLinks, List<DeadLink> deadLinks, List<EndingSoon> endingSoon, long moreDeadLinks,
                            long moreEndingSoon) {

    public static final String EXPIRES = "EXPIRES";
    public static final String VISIT_LIMIT = "VISIT_LIMIT";

    public WeeklySummary {
        topLinks = List.copyOf(topLinks);
        deadLinks = List.copyOf(deadLinks);
        endingSoon = List.copyOf(endingSoon);
    }

    public WeeklySummary(final long userId, final String email, final LocalDate from, final LocalDate to, final WeeklyDigest.Change clicks,
                         final long uniqueVisitors, final List<WeeklyDigest.TopLink> topLinks, final List<DeadLink> deadLinks,
                         final List<EndingSoon> endingSoon) {
        this(userId, email, from, to, clicks, uniqueVisitors, topLinks, deadLinks, endingSoon, 0, 0);
    }

    public boolean hasNews() {
        return clicks.current() > 0 || !deadLinks.isEmpty() || !endingSoon.isEmpty();
    }

    public record EndingSoon(String shortUrl, String destinationHost, String reason, LocalDate expiresOn, Long visitsLeft) {
    }

}
