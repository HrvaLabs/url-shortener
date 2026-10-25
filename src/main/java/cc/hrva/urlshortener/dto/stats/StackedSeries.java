package cc.hrva.urlshortener.dto.stats;

import java.util.List;

public record StackedSeries(List<String> keys, List<DailyCounts> daily) {
}
