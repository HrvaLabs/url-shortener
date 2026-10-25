package cc.hrva.urlshortener.repository.stats;

import java.time.LocalDate;

public record HourlyCount(LocalDate date, int hour, String key, long count) {
}
