package cc.hrva.urlshortener.dto.notification;

import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import java.util.List;

public record DeadLinksEmail(long userId, String email, List<DeadLink> links, String dashboardUrl) {

    public DeadLinksEmail {
        links = List.copyOf(links);
    }

}
