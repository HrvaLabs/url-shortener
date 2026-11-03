package cc.hrva.urlshortener.service.inspection;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

final class VirusTotalQuota {

    static final Duration KEPT_FOR = Duration.ofDays(2);
    private static final String KEY_PREFIX = "virustotal:";

    private VirusTotalQuota() {
    }

    static String keyOfToday(final Clock clock) {
        return KEY_PREFIX + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

}
