package cc.hrva.urlshortener.service.notification;

import java.util.List;

public record NotificationMessage(String title, List<String> lines, String link, int moreCount) {

    public NotificationMessage {
        lines = List.copyOf(lines);
    }

}
