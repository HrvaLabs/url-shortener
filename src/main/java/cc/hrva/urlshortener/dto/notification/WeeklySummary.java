package cc.hrva.urlshortener.dto.notification;

import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import java.time.LocalDate;
import java.util.List;

public record WeeklySummary(long userId, String email, LocalDate from, LocalDate to, WeeklyDigest.Change clicks, long uniqueVisitors,
                            List<WeeklyDigest.TopLink> topLinks, List<DeadLink> deadLinks, List<EndingSoon> endingSoon) {

    public static final String EXPIRES = "EXPIRES";
    public static final String VISIT_LIMIT = "VISIT_LIMIT";

    public WeeklySummary {
        topLinks = List.copyOf(topLinks);
        deadLinks = List.copyOf(deadLinks);
        endingSoon = List.copyOf(endingSoon);
    }

    public boolean hasNews() {
        return clicks.current() > 0 || !deadLinks.isEmpty() || !endingSoon.isEmpty();
    }

    public record EndingSoon(String shortUrl, String destinationHost, String reason, LocalDate expiresOn, Long visitsLeft) {
    }

}
