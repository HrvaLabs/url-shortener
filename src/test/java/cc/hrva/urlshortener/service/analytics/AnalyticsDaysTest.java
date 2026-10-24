package cc.hrva.urlshortener.service.analytics;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsDaysTest {

    private static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");

    @Test
    void todayFollowsZagrebMidnightNotUtc() {
        assertThat(analyticsDays("2026-10-24T22:30:00Z", ZAGREB).today()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(analyticsDays("2026-10-24T21:59:59Z", ZAGREB).today()).isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    void usesTheConfiguredZone() {
        final var analyticsDays = analyticsDays("2026-10-24T22:30:00Z", ZoneId.of("UTC"));

        assertThat(analyticsDays.zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(analyticsDays.today()).isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    void saltExpiresFourHoursAfterTheDayEnds() {
        assertThat(analyticsDays("2026-10-24T10:00:00Z", ZAGREB).saltExpiry(LocalDate.of(2026, 10, 24)))
                .isEqualTo(Instant.parse("2026-10-25T02:00:00Z"));
    }

    @Test
    void saltExpiryOnTheDstDay() {
        assertThat(analyticsDays("2026-10-25T10:00:00Z", ZAGREB).saltExpiry(LocalDate.of(2026, 10, 25)))
                .isEqualTo(Instant.parse("2026-10-26T03:00:00Z"));
    }

    private static AnalyticsDays analyticsDays(final String now, final ZoneId zone) {
        final var appProperties = new AppProperties();
        appProperties.getAnalytics().setZone(zone);

        return new AnalyticsDays(Clock.fixed(Instant.parse(now), ZoneOffset.UTC), appProperties);
    }

}
