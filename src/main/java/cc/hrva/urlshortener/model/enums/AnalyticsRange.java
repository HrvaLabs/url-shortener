package cc.hrva.urlshortener.model.enums;

import cc.hrva.urlshortener.exception.InvalidAnalyticsRangeException;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum AnalyticsRange {

    LAST_7_DAYS(7),
    LAST_30_DAYS(30),
    LAST_90_DAYS(90),
    LAST_365_DAYS(365);

    private static final String INVALID_RANGE = "days must be 7, 30, 90 or 365";

    private final int days;

    public int days() {
        return days;
    }

    public static AnalyticsRange fromDays(final Integer days) {
        if (days == null) {
            return LAST_30_DAYS;
        }
        return Arrays.stream(values())
                .filter(range -> range.days == days)
                .findFirst()
                .orElseThrow(() -> new InvalidAnalyticsRangeException(INVALID_RANGE));
    }

}
