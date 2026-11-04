package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.enums.InspectionState;
import cc.hrva.urlshortener.repository.LinkInspectionRepository;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import java.time.Clock;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionUsage {

    private final LinkInspectionRepository linkInspectionRepository;
    private final BrowserTimeUsed browserTimeUsed;
    private final RateLimiter rateLimiter;
    private final AppProperties appProperties;
    private final Clock clock;

    public OptionalLong screenshotsQueued() {
        return counted("screenshots", () -> linkInspectionRepository.countByScreenshotState(InspectionState.PENDING));
    }

    public OptionalLong virusTotalQueued() {
        return counted("VirusTotal lookups", () -> linkInspectionRepository.countByVtState(InspectionState.PENDING));
    }

    public OptionalDouble browserSecondsToday() {
        return browserTimeUsed.secondsToday();
    }

    public OptionalLong virusTotalLookupsToday() {
        final var counted = rateLimiter.count(VirusTotalQuota.keyOfToday(clock));
        if (counted.isEmpty()) {
            return counted;
        }
        return OptionalLong.of(Math.min(counted.getAsLong(), appProperties.getInspection().getVirusTotalDailyLimit()));
    }

    private static OptionalLong counted(final String queue, final LongSupplier count) {
        try {
            return OptionalLong.of(count.getAsLong());
        } catch (final RuntimeException exception) {
            log.warn("Could not count the queued {} exception={}", queue, exception.getClass().getName());
            return OptionalLong.empty();
        }
    }

}
