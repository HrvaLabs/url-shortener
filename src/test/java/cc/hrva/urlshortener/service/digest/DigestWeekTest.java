package cc.hrva.urlshortener.service.digest;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DigestWeekTest {

    private static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");

    @Test
    void onAMondayItIsTheWeekThatJustEnded() {
        final var week = DigestWeek.lastClosedBefore(LocalDate.of(2026, 10, 26));

        assertThat(week).isEqualTo(new DigestWeek(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)));
        assertThat(week.previous()).isEqualTo(new DigestWeek(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)));
    }

    @Test
    void onASundayItIsTheWeekBefore() {
        assertThat(DigestWeek.lastClosedBefore(LocalDate.of(2026, 10, 25)))
                .isEqualTo(new DigestWeek(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)));
    }

    @Test
    void midweekItIsLastWeek() {
        assertThat(DigestWeek.lastClosedBefore(LocalDate.of(2026, 10, 29)))
                .isEqualTo(new DigestWeek(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)));
    }

    @Test
    void aWeekAcrossTheNewYear() {
        final var week = DigestWeek.lastClosedBefore(LocalDate.of(2026, 1, 5));

        assertThat(week).isEqualTo(new DigestWeek(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)));
        assertThat(week.previous()).isEqualTo(new DigestWeek(LocalDate.of(2025, 12, 22), LocalDate.of(2025, 12, 28)));
    }

    @Test
    void aSundayAtOneMinuteToMidnightInZagrebStillReportsTheWeekBefore() {
        assertThat(weekAt("2026-10-25T22:59:59Z"))
                .isEqualTo(new DigestWeek(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)));
    }

    @Test
    void mondayAtMidnightInZagrebReportsTheWeekThatJustEndedThoughItIsStillSundayInUtc() {
        assertThat(weekAt("2026-10-25T23:00:00Z"))
                .isEqualTo(new DigestWeek(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)));
    }

    @Test
    void theWeekOfTheSpringDstChangeTurnsAtZagrebMidnight() {
        assertThat(weekAt("2026-03-29T21:59:59Z"))
                .isEqualTo(new DigestWeek(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 22)));
        assertThat(weekAt("2026-03-29T22:00:00Z"))
                .isEqualTo(new DigestWeek(LocalDate.of(2026, 3, 23), LocalDate.of(2026, 3, 29)));
    }

    @Test
    void theWeekAcrossTheNewYearTurnsAtZagrebMidnight() {
        assertThat(weekAt("2026-01-04T22:59:59Z"))
                .isEqualTo(new DigestWeek(LocalDate.of(2025, 12, 22), LocalDate.of(2025, 12, 28)));
        assertThat(weekAt("2026-01-04T23:00:00Z"))
                .isEqualTo(new DigestWeek(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)));
    }

    @Test
    void everyDayGivesTheLastClosedMondayToSunday() {
        LocalDate.of(2025, 1, 1).datesUntil(LocalDate.of(2028, 1, 1)).forEach(today -> {
            final var week = DigestWeek.lastClosedBefore(today);

            assertThat(week.from().getDayOfWeek()).as("from of %s", today).isEqualTo(DayOfWeek.MONDAY);
            assertThat(week.to().getDayOfWeek()).as("to of %s", today).isEqualTo(DayOfWeek.SUNDAY);
            assertThat(ChronoUnit.DAYS.between(week.from(), week.to())).as("length of %s", today).isEqualTo(6);
            assertThat(ChronoUnit.DAYS.between(week.to(), today)).as("days since %s", today).isBetween(1L, 7L);
            assertThat(week.previous().to()).as("previous of %s", today).isEqualTo(week.from().minusDays(1));
        });
    }

    private static DigestWeek weekAt(final String instant) {
        final var appProperties = new AppProperties();
        appProperties.getAnalytics().setZone(ZAGREB);
        final var analyticsDays = new AnalyticsDays(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), appProperties);
        return DigestWeek.lastClosedBefore(analyticsDays.today());
    }

}
