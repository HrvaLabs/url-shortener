package cc.hrva.urlshortener.service.analytics;

import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
import cc.hrva.urlshortener.model.enums.StatDimension;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class AnalyticsSeries {

    private static final int TOP_VALUES_LIMIT = 10;
    private static final String OTHER = "other";

    private AnalyticsSeries() {}

    public static List<AnalyticsDay> zeroFilled(final List<AnalyticsDay> stored, final LocalDate from, final LocalDate to) {
        final var storedByDate = stored.stream()
                .collect(Collectors.toMap(AnalyticsDay::date, Function.identity()));

        return from.datesUntil(to.plusDays(1))
                .map(date -> storedByDate.getOrDefault(date, new AnalyticsDay(date, 0, 0, 0)))
                .toList();
    }

    public static AnalyticsTotals totalsOf(final List<AnalyticsDay> stored) {
        return new AnalyticsTotals(
                stored.stream().mapToLong(AnalyticsDay::clicks).sum(),
                stored.stream().mapToLong(AnalyticsDay::uniqueVisitors).sum(),
                stored.stream().mapToLong(AnalyticsDay::botClicks).sum());
    }

    public static List<BreakdownEntry> topValues(final Map<StatDimension, List<BreakdownEntry>> breakdowns, final StatDimension dimension) {
        final var entries = breakdowns.getOrDefault(dimension, List.of());
        final var named = entries.stream().filter(entry -> !OTHER.equals(entry.value())).toList();
        final var storedOther = entries.stream().filter(entry -> OTHER.equals(entry.value())).mapToLong(BreakdownEntry::clicks).sum();
        final var leftover = named.stream().skip(TOP_VALUES_LIMIT).mapToLong(BreakdownEntry::clicks).sum();

        final var top = new ArrayList<>(named.stream().limit(TOP_VALUES_LIMIT).toList());
        if (storedOther + leftover > 0) {
            top.add(new BreakdownEntry(OTHER, storedOther + leftover));
        }
        return List.copyOf(top);
    }

    public static Breakdowns breakdownsOf(final Map<StatDimension, List<BreakdownEntry>> breakdowns) {
        return new Breakdowns(
                topValues(breakdowns, StatDimension.REFERRER),
                topValues(breakdowns, StatDimension.COUNTRY),
                topValues(breakdowns, StatDimension.DEVICE),
                topValues(breakdowns, StatDimension.BROWSER),
                topValues(breakdowns, StatDimension.OS),
                topValues(breakdowns, StatDimension.SOURCE));
    }

}
