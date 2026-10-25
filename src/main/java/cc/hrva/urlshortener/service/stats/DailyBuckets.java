package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.stats.DailyCounts;
import cc.hrva.urlshortener.dto.stats.StackedSeries;
import cc.hrva.urlshortener.repository.stats.HourlyCount;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DailyBuckets {

    private final StatsDays statsDays;

    public StackedSeries stack(final List<HourlyCount> rows, final List<String> knownKeys, final StatsWindow window) {
        final var totalsByDay = rows.stream()
                .map(row -> new DayCount(statsDays.dayOfStored(row.date().atTime(row.hour(), 0)), row.key(), row.count()))
                .filter(count -> !count.day().isBefore(window.from()) && !count.day().isAfter(window.to()))
                .collect(Collectors.groupingBy(DayCount::day,
                        Collectors.groupingBy(DayCount::key, Collectors.summingLong(DayCount::count))));
        final var otherKeys = totalsByDay.values().stream()
                .flatMap(totals -> totals.keySet().stream())
                .filter(key -> !knownKeys.contains(key))
                .distinct()
                .sorted()
                .toList();
        final var keys = Stream.concat(knownKeys.stream(), otherKeys.stream()).toList();

        final var daily = window.from().datesUntil(window.to().plusDays(1))
                .map(date -> new DailyCounts(date, countsOf(keys, totalsByDay.getOrDefault(date, Map.of()))))
                .toList();
        return new StackedSeries(keys, daily);
    }

    private static Map<String, Long> countsOf(final List<String> keys, final Map<String, Long> totals) {
        final var counts = new LinkedHashMap<String, Long>();
        keys.forEach(key -> counts.put(key, totals.getOrDefault(key, 0L)));
        return counts;
    }

    private record DayCount(LocalDate day, String key, long count) {}

}
