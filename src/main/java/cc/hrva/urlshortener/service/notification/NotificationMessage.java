package cc.hrva.urlshortener.service.notification;

import java.util.Collections;
import java.util.List;

public record NotificationMessage(String title, List<String> lines, String link, int moreCount, List<Integer> linkCounts, List<Integer> headings) {

    public NotificationMessage {
        lines = List.copyOf(lines);
        linkCounts = linkCounts == null || linkCounts.size() != lines.size() ? Collections.nCopies(lines.size(), 1) : List.copyOf(linkCounts);
        headings = headings == null ? List.of() : List.copyOf(headings);
    }

    public NotificationMessage(final String title, final List<String> lines, final String link, final int moreCount) {
        this(title, lines, link, moreCount, null, null);
    }

}
