package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.model.enums.AnalyticsRange;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class StatsDays {

    private final AnalyticsDays analyticsDays;
    private final Clock clock;
    private final ZoneId storageZone;

    @Autowired
    public StatsDays(final AnalyticsDays analyticsDays, final Clock clock) {
        this(analyticsDays, clock, ZoneId.systemDefault());
    }

    StatsDays(final AnalyticsDays analyticsDays, final Clock clock, final ZoneId storageZone) {
        this.analyticsDays = analyticsDays;
        this.clock = clock;
        this.storageZone = storageZone;
    }

    public LocalDateTime storedStartOf(final LocalDate day) {
        return day.atStartOfDay(analyticsDays.zone()).withZoneSameInstant(storageZone).toLocalDateTime();
    }

    public LocalDate dayOfStored(final LocalDateTime stored) {
        return stored.atZone(storageZone).withZoneSameInstant(analyticsDays.zone()).toLocalDate();
    }

    public StatsWindow window(final AnalyticsRange range) {
        final var to = analyticsDays.today();
        return window(to.minusDays(range.days() - 1L), to);
    }

    public StatsWindow window(final LocalDate from, final LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        final var days = Math.toIntExact(ChronoUnit.DAYS.between(from, to) + 1);
        return new StatsWindow(days, from, to, storedStartOf(from), storedStartOf(to.plusDays(1)));
    }

    public LocalDateTime storedNow() {
        return LocalDateTime.now(clock.withZone(storageZone));
    }

}
