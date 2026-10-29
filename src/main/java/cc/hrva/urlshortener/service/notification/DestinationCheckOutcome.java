package cc.hrva.urlshortener.service.notification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DestinationCheckOutcome(List<DeadLink> newlyDead, List<DeadLink> recovered, LocalDate night) {

    public static DestinationCheckOutcome nothing(final LocalDate night) {
        return new DestinationCheckOutcome(List.of(), List.of(), night);
    }

    public record DeadLink(long urlId, Long ownerId, String shortUrl, String destinationHost, LocalDateTime deadSince,
                           String lastStatus) {
    }

}
