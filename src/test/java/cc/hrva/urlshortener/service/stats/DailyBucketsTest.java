package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.stats.DailyCounts;
import cc.hrva.urlshortener.dto.stats.StackedSeries;
import cc.hrva.urlshortener.model.enums.AnalyticsRange;
import cc.hrva.urlshortener.repository.stats.HourlyCount;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class DailyBucketsTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final List<String> KNOWN = List.of("WEB", "API_KEY", "unknown");
    private static final LocalDate FROM = LocalDate.of(2026, 10, 20);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final StatsDays statsDays = new StatsDays(new AnalyticsDays(clock, appProperties()), clock, ZoneOffset.UTC);
    private final StatsWindow window = statsDays.window(AnalyticsRange.LAST_7_DAYS);
    private final DailyBuckets dailyBuckets = new DailyBuckets(statsDays);

    @Test
    void zeroFillsEveryDayAndKey() {
        final var series = dailyBuckets.stack(List.of(), KNOWN, window);

        assertThat(series.keys()).containsExactlyElementsOf(KNOWN);
        assertThat(series.daily()).extracting(DailyCounts::date)
                .containsExactlyElementsOf(FROM.datesUntil(TO.plusDays(1)).toList());
        assertThat(series.daily()).allSatisfy(day ->
                assertThat(day.counts()).containsExactly(entry("WEB", 0L), entry("API_KEY", 0L), entry("unknown", 0L)));
    }

    @Test
    void assignsEachHourToItsZagrebDay() {
        final var series = dailyBuckets.stack(List.of(
                new HourlyCount(LocalDate.of(2026, 10, 24), 22, "WEB", 2),
                new HourlyCount(LocalDate.of(2026, 10, 24), 21, "WEB", 1)), KNOWN, window);

        assertThat(countsOn(series, LocalDate.of(2026, 10, 25))).containsExactly(entry("WEB", 2L), entry("API_KEY", 0L), entry("unknown", 0L));
        assertThat(countsOn(series, LocalDate.of(2026, 10, 24))).containsExactly(entry("WEB", 1L), entry("API_KEY", 0L), entry("unknown", 0L));
    }

    @Test
    void sumsTheHoursOfOneDay() {
        final var series = dailyBuckets.stack(List.of(
                new HourlyCount(LocalDate.of(2026, 10, 21), 22, "WEB", 4),
                new HourlyCount(LocalDate.of(2026, 10, 22), 8, "WEB", 2),
                new HourlyCount(LocalDate.of(2026, 10, 22), 9, "API_KEY", 1),
                new HourlyCount(LocalDate.of(2026, 10, 22), 15, "WEB", 3),
                new HourlyCount(LocalDate.of(2026, 10, 22), 21, "WEB", 7)), KNOWN, window);

        assertThat(countsOn(series, LocalDate.of(2026, 10, 22))).containsExactly(entry("WEB", 16L), entry("API_KEY", 1L), entry("unknown", 0L));
        assertThat(countsOn(series, LocalDate.of(2026, 10, 21))).containsExactly(entry("WEB", 0L), entry("API_KEY", 0L), entry("unknown", 0L));
        assertThat(countsOn(series, LocalDate.of(2026, 10, 23))).containsExactly(entry("WEB", 0L), entry("API_KEY", 0L), entry("unknown", 0L));
    }

    @Test
    void aTwentyFiveHourDayIsOneDay() {
        final var firstHour = LocalDateTime.of(2026, 10, 24, 22, 0);
        final var hours = IntStream.range(0, 25)
                .mapToObj(firstHour::plusHours)
                .map(hour -> new HourlyCount(hour.toLocalDate(), hour.getHour(), "WEB", 1))
                .toList();

        final var series = dailyBuckets.stack(hours, KNOWN, window);

        assertThat(hours.getLast()).isEqualTo(new HourlyCount(LocalDate.of(2026, 10, 25), 22, "WEB", 1));
        assertThat(series.daily()).hasSize(7);
        assertThat(countsOn(series, LocalDate.of(2026, 10, 25))).containsEntry("WEB", 25L);
        assertThat(countsOn(series, LocalDate.of(2026, 10, 24))).containsEntry("WEB", 0L);
        assertThat(countsOn(series, LocalDate.of(2026, 10, 26))).containsEntry("WEB", 0L);
    }

    @Test
    void appendsUnexpectedKeysAfterTheKnownOnes() {
        final var series = dailyBuckets.stack(List.of(new HourlyCount(LocalDate.of(2026, 10, 23), 10, "BOUNCED", 3)), KNOWN, window);

        assertThat(series.keys()).containsExactly("WEB", "API_KEY", "unknown", "BOUNCED");
        assertThat(countsOn(series, LocalDate.of(2026, 10, 23)))
                .containsExactly(entry("WEB", 0L), entry("API_KEY", 0L), entry("unknown", 0L), entry("BOUNCED", 3L));
        assertThat(series.daily()).filteredOn(day -> !day.date().equals(LocalDate.of(2026, 10, 23))).hasSize(6).allSatisfy(day ->
                assertThat(day.counts()).containsExactly(entry("WEB", 0L), entry("API_KEY", 0L), entry("unknown", 0L), entry("BOUNCED", 0L)));
    }

    @Test
    void sortsUnexpectedKeysAlphabetically() {
        final var series = dailyBuckets.stack(List.of(
                new HourlyCount(LocalDate.of(2026, 10, 23), 10, "ZEBRA", 1),
                new HourlyCount(LocalDate.of(2026, 10, 23), 11, "BOUNCED", 1),
                new HourlyCount(LocalDate.of(2026, 10, 24), 10, "ARCHIVED", 1),
                new HourlyCount(LocalDate.of(2026, 10, 24), 11, "WEB", 1)), KNOWN, window);

        assertThat(series.keys()).containsExactly("WEB", "API_KEY", "unknown", "ARCHIVED", "BOUNCED", "ZEBRA");
    }

    @Test
    void dropsRowsOutsideTheWindow() {
        final var series = dailyBuckets.stack(List.of(
                new HourlyCount(LocalDate.of(2026, 10, 19), 21, "WEB", 4),
                new HourlyCount(LocalDate.of(2026, 10, 19), 22, "WEB", 1),
                new HourlyCount(LocalDate.of(2026, 10, 26), 22, "WEB", 1),
                new HourlyCount(LocalDate.of(2026, 10, 26), 23, "WEB", 6),
                new HourlyCount(LocalDate.of(2026, 10, 27), 3, "LATE", 5)), KNOWN, window);

        assertThat(series.keys()).containsExactlyElementsOf(KNOWN);
        assertThat(series.daily()).extracting(DailyCounts::date)
                .containsExactlyElementsOf(FROM.datesUntil(TO.plusDays(1)).toList());
        assertThat(countsOn(series, FROM)).containsEntry("WEB", 1L);
        assertThat(countsOn(series, TO)).containsEntry("WEB", 1L);
        assertThat(series.daily().stream().flatMap(day -> day.counts().values().stream()).mapToLong(Long::longValue).sum()).isEqualTo(2);
    }

    private static Map<String, Long> countsOn(final StackedSeries series, final LocalDate date) {
        return series.daily().stream()
                .filter(day -> day.date().equals(date))
                .findFirst()
                .orElseThrow()
                .counts();
    }

    private static AppProperties appProperties() {
        final var appProperties = new AppProperties();
        appProperties.getAnalytics().setZone(ZoneId.of("Europe/Zagreb"));
        return appProperties;
    }

}
