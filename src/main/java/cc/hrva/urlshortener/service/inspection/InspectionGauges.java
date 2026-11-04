package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InspectionGauges implements MeterBinder {

    public static final String QUEUE = "hrva.inspection.queue";
    public static final String BROWSER_USED_TODAY = "hrva.browser.used.today";
    public static final String VIRUSTOTAL_LOOKUPS_TODAY = "hrva.virustotal.lookups.today";
    private static final String QUEUE_TAG = "queue";
    private static final String SECONDS = "seconds";

    private final InspectionUsage inspectionUsage;
    private final AppProperties appProperties;
    private final AtomicReference<Readings> readings = new AtomicReference<>(Readings.UNKNOWN);

    @Override
    public void bindTo(final MeterRegistry registry) {
        gauge(QUEUE, Readings::screenshotsQueued).tag(QUEUE_TAG, "screenshots")
                .description("Screenshots waiting to be taken").register(registry);
        gauge(QUEUE, Readings::virusTotalQueued).tag(QUEUE_TAG, "virustotal")
                .description("VirusTotal lookups waiting to run").register(registry);
        gauge(BROWSER_USED_TODAY, Readings::browserSecondsToday).baseUnit(SECONDS)
                .description("Cloudflare Browser Run time used today (UTC)").register(registry);
        gauge(VIRUSTOTAL_LOOKUPS_TODAY, Readings::virusTotalLookupsToday)
                .description("VirusTotal lookups made today (UTC)").register(registry);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void refreshInspectionGauges() {
        if (StringUtils.isBlank(appProperties.getMetrics().getToken())) {
            return;
        }
        readings.set(new Readings(valueOf(inspectionUsage.screenshotsQueued()), valueOf(inspectionUsage.virusTotalQueued()),
                valueOf(inspectionUsage.browserSecondsToday()), valueOf(inspectionUsage.virusTotalLookupsToday())));
    }

    private Gauge.Builder<AtomicReference<Readings>> gauge(final String name, final ToDoubleFunction<Readings> value) {
        return Gauge.builder(name, readings, current -> value.applyAsDouble(current.get())).strongReference(true);
    }

    private static double valueOf(final OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : Double.NaN;
    }

    private static double valueOf(final OptionalDouble value) {
        return value.orElse(Double.NaN);
    }

    private record Readings(double screenshotsQueued, double virusTotalQueued, double browserSecondsToday, double virusTotalLookupsToday) {

        private static final Readings UNKNOWN = new Readings(Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    }

}
