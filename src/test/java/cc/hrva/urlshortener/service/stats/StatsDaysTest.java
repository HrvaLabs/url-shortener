package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.enums.AnalyticsRange;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.digest.DigestWeek;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatsDaysTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AppProperties appProperties = appProperties();
    private final AnalyticsDays analyticsDays = new AnalyticsDays(clock, appProperties);
    private final StatsDays statsDays = new StatsDays(analyticsDays, clock, ZoneOffset.UTC);

    @Test
    void storedStartOfFollowsZagrebMidnight() {
        assertThat(statsDays.storedStartOf(LocalDate.of(2026, 10, 25))).isEqualTo(LocalDateTime.of(2026, 10, 24, 22, 0));
        assertThat(statsDays.storedStartOf(LocalDate.of(2026, 10, 26))).isEqualTo(LocalDateTime.of(2026, 10, 25, 23, 0));
    }

    @Test
    void dayOfStoredFollowsZagrebDays() {
        assertThat(statsDays.dayOfStored(LocalDateTime.of(2026, 10, 24, 22, 30))).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(statsDays.dayOfStored(LocalDateTime.of(2026, 10, 24, 21, 59))).isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    void aSevenDayWindowAcrossTheDstChange() {
        assertThat(statsDays.window(AnalyticsRange.LAST_7_DAYS)).isEqualTo(new StatsWindow(
                7,
                LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 26),
                LocalDateTime.of(2026, 10, 19, 22, 0),
                LocalDateTime.of(2026, 10, 26, 23, 0)));
    }

    @Test
    void aClosedWeekAcrossTheDstChange() {
        assertThat(statsDays.window(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25))).isEqualTo(new StatsWindow(
                7,
                LocalDate.of(2026, 10, 19),
                LocalDate.of(2026, 10, 25),
                LocalDateTime.of(2026, 10, 18, 22, 0),
                LocalDateTime.of(2026, 10, 25, 23, 0)));
    }

    @Test
    void theDigestWeeksOfBothDstChangesAreSevenZagrebDays() {
        final var autumn = DigestWeek.lastClosedBefore(LocalDate.of(2026, 10, 26));
        final var spring = DigestWeek.lastClosedBefore(LocalDate.of(2026, 3, 30));

        assertThat(statsDays.window(autumn.from(), autumn.to())).isEqualTo(new StatsWindow(
                7,
                LocalDate.of(2026, 10, 19),
                LocalDate.of(2026, 10, 25),
                LocalDateTime.of(2026, 10, 18, 22, 0),
                LocalDateTime.of(2026, 10, 25, 23, 0)));
        assertThat(statsDays.window(spring.from(), spring.to())).isEqualTo(new StatsWindow(
                7,
                LocalDate.of(2026, 3, 23),
                LocalDate.of(2026, 3, 29),
                LocalDateTime.of(2026, 3, 22, 23, 0),
                LocalDateTime.of(2026, 3, 29, 22, 0)));
    }

    @Test
    void aSingleDayWindowIsOneDay() {
        assertThat(statsDays.window(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25))).isEqualTo(new StatsWindow(
                1,
                LocalDate.of(2026, 10, 25),
                LocalDate.of(2026, 10, 25),
                LocalDateTime.of(2026, 10, 24, 22, 0),
                LocalDateTime.of(2026, 10, 25, 23, 0)));
    }

    @Test
    void fromAfterToIsRefused() {
        assertThatThrownBy(() -> statsDays.window(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("from must not be after to");
    }

    @Test
    void storedNowIsTheClockInTheStorageZone() {
        assertThat(statsDays.storedNow()).isEqualTo(LocalDateTime.of(2026, 10, 26, 10, 0));
        assertThat(new StatsDays(analyticsDays, clock, ZAGREB).storedNow()).isEqualTo(LocalDateTime.of(2026, 10, 26, 11, 0));
    }

    @Test
    void springCreatesItWithTheJvmZoneAsStorage() {
        new ApplicationContextRunner()
                .withBean(Clock.class, () -> clock)
                .withBean(AppProperties.class, () -> appProperties)
                .withUserConfiguration(AnalyticsDays.class, StatsDays.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StatsDays.class).storedNow()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneId.systemDefault()));
                });
    }

    private static AppProperties appProperties() {
        final var appProperties = new AppProperties();
        appProperties.getAnalytics().setZone(ZAGREB);
        return appProperties;
    }

}
