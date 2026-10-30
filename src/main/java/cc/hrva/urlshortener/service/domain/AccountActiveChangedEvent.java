package cc.hrva.urlshortener.service.domain;

import java.util.List;

public record AccountActiveChangedEvent(List<Long> userIds) {
}
