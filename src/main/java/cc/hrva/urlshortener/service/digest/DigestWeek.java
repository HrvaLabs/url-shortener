package cc.hrva.urlshortener.service.digest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

public record DigestWeek(LocalDate from, LocalDate to) {

    private static final int DAYS_AFTER_MONDAY = 6;
    private static final int DAYS_IN_WEEK = 7;

    public static DigestWeek lastClosedBefore(final LocalDate today) {
        final var to = today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
        return new DigestWeek(to.minusDays(DAYS_AFTER_MONDAY), to);
    }

    public DigestWeek previous() {
        return new DigestWeek(from.minusDays(DAYS_IN_WEEK), to.minusDays(DAYS_IN_WEEK));
    }

}
