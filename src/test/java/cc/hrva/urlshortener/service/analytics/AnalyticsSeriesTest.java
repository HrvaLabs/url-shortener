package cc.hrva.urlshortener.service.analytics;

import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.model.enums.StatDimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsSeriesTest {

    @Test
    void countryMapKeepsEveryNamedCountryPastTheTenth() {
        final var entries = new ArrayList<BreakdownEntry>();
        IntStream.range(0, 15).forEach(index -> entries.add(new BreakdownEntry("C" + (char) ('A' + index), 100 - index)));

        final var countryMap = AnalyticsSeries.countryMap(Map.of(StatDimension.COUNTRY, entries));

        assertThat(countryMap).containsExactlyElementsOf(entries).hasSize(15);
    }

    @Test
    void countryMapDropsOtherUnknownAndTor() {
        final var countryMap = AnalyticsSeries.countryMap(Map.of(StatDimension.COUNTRY, List.of(
                new BreakdownEntry("HR", 5), new BreakdownEntry("other", 40), new BreakdownEntry("unknown", 30),
                new BreakdownEntry("tor", 20), new BreakdownEntry("DE", 2))));

        assertThat(countryMap).containsExactly(new BreakdownEntry("HR", 5), new BreakdownEntry("DE", 2));
    }

    @Test
    void countryMapOrdersClicksDescendingThenCodeAscending() {
        final var countryMap = AnalyticsSeries.countryMap(Map.of(StatDimension.COUNTRY, List.of(
                new BreakdownEntry("SE", 3), new BreakdownEntry("DE", 3), new BreakdownEntry("HR", 9), new BreakdownEntry("AT", 3))));

        assertThat(countryMap).containsExactly(
                new BreakdownEntry("HR", 9), new BreakdownEntry("AT", 3), new BreakdownEntry("DE", 3), new BreakdownEntry("SE", 3));
    }

    @Test
    void countryMapIsEmptyWithoutData() {
        assertThat(AnalyticsSeries.countryMap(Map.of())).isEmpty();
    }

    @Test
    void breakdownsCarryTheCountryMapAndKeepTheTopTenCountries() {
        final var entries = new ArrayList<BreakdownEntry>();
        IntStream.range(0, 12).forEach(index -> entries.add(new BreakdownEntry("C" + (char) ('A' + index), 50 - index)));

        final var breakdowns = AnalyticsSeries.breakdownsOf(Map.of(StatDimension.COUNTRY, entries));

        assertThat(breakdowns.countries()).hasSize(11).last().isEqualTo(new BreakdownEntry("other", 40 + 39));
        assertThat(breakdowns.countryMap()).containsExactlyElementsOf(entries);
    }

}
