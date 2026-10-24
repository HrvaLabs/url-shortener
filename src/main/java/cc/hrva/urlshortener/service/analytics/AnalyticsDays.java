package cc.hrva.urlshortener.service.analytics;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalyticsDays {

    private static final Duration SALT_GRACE = Duration.ofHours(4);

    private final Clock clock;
    private final AppProperties appProperties;

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }

    public ZoneId zone() {
        return appProperties.getAnalytics().getZone();
    }

    public Instant saltExpiry(final LocalDate day) {
        return day.plusDays(1).atStartOfDay(zone()).plus(SALT_GRACE).toInstant();
    }

}
