package cc.hrva.urlshortener.dto.stats;

import java.time.LocalDate;
import java.util.Map;

public record DailyCounts(LocalDate date, Map<String, Long> counts) {
}
