package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.jobs.JobFailedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InspectionGaugesTest {

    private final InspectionUsage inspectionUsage = mock(InspectionUsage.class);
    private final AppProperties appProperties = new AppProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InspectionGauges gauges = new InspectionGauges(inspectionUsage, appProperties);

    @BeforeEach
    void setUp() {
        appProperties.getMetrics().setToken("metrics-token");
        gauges.bindTo(registry);
    }

    @Test
    void everyGaugeIsUnknownBeforeTheFirstRefresh() {
        assertThat(queue("screenshots")).isNaN();
        assertThat(queue("virustotal")).isNaN();
        assertThat(registry.get(InspectionGauges.BROWSER_USED_TODAY).gauge().value()).isNaN();
        assertThat(registry.get(InspectionGauges.VIRUSTOTAL_LOOKUPS_TODAY).gauge().value()).isNaN();
        verifyNoInteractions(inspectionUsage);
    }

    @Test
    void aRefreshFillsTheGaugesAndAScrapeReadsOnlyTheRefreshedValues() {
        when(inspectionUsage.screenshotsQueued()).thenReturn(OptionalLong.of(4));
        when(inspectionUsage.virusTotalQueued()).thenReturn(OptionalLong.of(7));
        when(inspectionUsage.browserSecondsToday()).thenReturn(OptionalDouble.of(42.5));
        when(inspectionUsage.virusTotalLookupsToday()).thenReturn(OptionalLong.of(3));

        gauges.refreshInspectionGauges();
        when(inspectionUsage.screenshotsQueued()).thenThrow(new IllegalStateException("a scrape must not read"));

        assertThat(queue("screenshots")).isEqualTo(4.0);
        assertThat(queue("virustotal")).isEqualTo(7.0);
        assertThat(registry.get(InspectionGauges.BROWSER_USED_TODAY).gauge().value()).isEqualTo(42.5);
        assertThat(registry.get(InspectionGauges.BROWSER_USED_TODAY).gauge().getId().getBaseUnit()).isEqualTo("seconds");
        assertThat(registry.get(InspectionGauges.VIRUSTOTAL_LOOKUPS_TODAY).gauge().value()).isEqualTo(3.0);
    }

    @Test
    void aValueThatCouldNotBeReadIsUnknown() {
        when(inspectionUsage.screenshotsQueued()).thenReturn(OptionalLong.empty());
        when(inspectionUsage.virusTotalQueued()).thenReturn(OptionalLong.of(0));
        when(inspectionUsage.browserSecondsToday()).thenReturn(OptionalDouble.empty());
        when(inspectionUsage.virusTotalLookupsToday()).thenReturn(OptionalLong.empty());

        assertThatThrownBy(gauges::refreshInspectionGauges).isInstanceOf(JobFailedException.class)
                .hasMessage("The database wasn't reachable, so the queue sizes are unknown. Redis wasn't reachable, so today's usage is unknown.");

        assertThat(queue("screenshots")).isNaN();
        assertThat(queue("virustotal")).isZero();
        assertThat(registry.get(InspectionGauges.BROWSER_USED_TODAY).gauge().value()).isNaN();
        assertThat(registry.get(InspectionGauges.VIRUSTOTAL_LOOKUPS_TODAY).gauge().value()).isNaN();
    }

    @Test
    void onlyTheSideThatCouldNotBeReadIsNamed() {
        when(inspectionUsage.screenshotsQueued()).thenReturn(OptionalLong.of(1));
        when(inspectionUsage.virusTotalQueued()).thenReturn(OptionalLong.of(0));
        when(inspectionUsage.browserSecondsToday()).thenReturn(OptionalDouble.of(1));
        when(inspectionUsage.virusTotalLookupsToday()).thenReturn(OptionalLong.empty());

        assertThatThrownBy(gauges::refreshInspectionGauges).isInstanceOf(JobFailedException.class)
                .hasMessage("Redis wasn't reachable, so today's usage is unknown.");
        assertThat(queue("screenshots")).isEqualTo(1.0);
    }

    @Test
    void withoutAMetricsTokenNothingIsRead() {
        appProperties.getMetrics().setToken(" ");

        gauges.refreshInspectionGauges();

        verifyNoInteractions(inspectionUsage);
        assertThat(queue("screenshots")).isNaN();
    }

    private double queue(final String queue) {
        return registry.get(InspectionGauges.QUEUE).tag("queue", queue).gauge().value();
    }

}
