package cc.hrva.urlshortener.service.jobs;

import cc.hrva.urlshortener.service.notification.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobRunsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 22, 31);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-03T22:31:00Z"));
    private final JobRuns jobRuns = new JobRuns(clock);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void everyLabelledJobIsListedInLabelOrderBeforeItsFirstRun() {
        final var runs = jobRuns.all();

        assertThat(runs).hasSize(JobLabels.all().size());
        assertThat(runs.getFirst()).isEqualTo(new JobRun("deactivateExpiredUrls", "Turn off expired links", null, null, null, null));
        assertThat(runs).extracting(JobRun::name).containsExactlyElementsOf(JobLabels.all().keySet());
    }

    @Test
    void aRunningJobHasAStartAfterItsLastFinish() {
        jobRuns.started("deactivateExpiredUrls");
        clock.advance(Duration.ofMillis(250));
        jobRuns.finished("deactivateExpiredUrls");
        clock.advance(Duration.ofMinutes(1));
        jobRuns.started("deactivateExpiredUrls");

        assertThat(jobRuns.all().getFirst()).isEqualTo(new JobRun("deactivateExpiredUrls", "Turn off expired links", NOW.plusMinutes(1).plusNanos(250_000_000),
                NOW.plusNanos(250_000_000), JobOutcome.OK, null));
    }

    @Test
    void aFailedRunKeepsItsErrorUntilTheNextFailure() {
        jobRuns.started("refreshDueBlocklists");
        jobRuns.failed("refreshDueBlocklists", new IllegalStateException("Redis is down"));

        assertThat(run("refreshDueBlocklists")).isEqualTo(new JobRun("refreshDueBlocklists", "Refresh due blocklists", NOW, NOW, JobOutcome.FAILED,
                "java.lang.IllegalStateException: Redis is down"));

        clock.advance(Duration.ofHours(1));
        jobRuns.started("refreshDueBlocklists");
        jobRuns.finished("refreshDueBlocklists");

        assertThat(run("refreshDueBlocklists").lastOutcome()).isEqualTo(JobOutcome.OK);
        assertThat(run("refreshDueBlocklists").lastFinishedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(run("refreshDueBlocklists").lastError()).isEqualTo("java.lang.IllegalStateException: Redis is down");
    }

    @Test
    void aJobWithoutALabelComesLastUnderItsMethodName() {
        jobRuns.started("zeta");
        jobRuns.started("alpha");
        jobRuns.finished("alpha");

        final var runs = jobRuns.all();

        assertThat(runs).hasSize(JobLabels.all().size() + 2);
        assertThat(runs.get(runs.size() - 2)).isEqualTo(new JobRun("alpha", "alpha", NOW, NOW, JobOutcome.OK, null));
        assertThat(runs.getLast()).isEqualTo(new JobRun("zeta", "zeta", NOW, null, null, null));
    }

    @Test
    void eachJobHasAnOutcomeAndALastRunGauge() {
        jobRuns.bindTo(registry);

        assertThat(registry.get(JobRuns.LAST_OUTCOME).tag("job", "deactivateExpiredUrls").gauge().value()).isNaN();
        assertThat(registry.get(JobRuns.LAST_RUN).tag("job", "deactivateExpiredUrls").gauge().value()).isNaN();
        assertThat(registry.get(JobRuns.LAST_RUN).tag("job", "deactivateExpiredUrls").gauge().getId().getBaseUnit()).isEqualTo("seconds");
        assertThat(registry.get(JobRuns.LAST_OUTCOME).gauges()).hasSize(JobLabels.all().size());

        jobRuns.started("deactivateExpiredUrls");
        jobRuns.finished("deactivateExpiredUrls");
        jobRuns.started("refreshDueBlocklists");
        jobRuns.failed("refreshDueBlocklists", new IllegalStateException("down"));

        assertThat(registry.get(JobRuns.LAST_OUTCOME).tag("job", "deactivateExpiredUrls").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(JobRuns.LAST_OUTCOME).tag("job", "refreshDueBlocklists").gauge().value()).isZero();
        assertThat(registry.get(JobRuns.LAST_RUN).tag("job", "refreshDueBlocklists").gauge().value())
                .isEqualTo((double) Instant.parse("2026-11-03T22:31:00Z").getEpochSecond());
    }

    @Test
    void aJobFirstSeenAfterBindingGetsItsGaugesToo() {
        jobRuns.started("seenBefore");
        jobRuns.bindTo(registry);
        jobRuns.started("seenAfter");
        jobRuns.finished("seenAfter");

        assertThat(registry.get(JobRuns.LAST_OUTCOME).tag("job", "seenBefore").gauge().value()).isNaN();
        assertThat(registry.get(JobRuns.LAST_OUTCOME).tag("job", "seenAfter").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(JobRuns.LAST_OUTCOME).gauges()).hasSize(JobLabels.all().size() + 2);
    }

    private JobRun run(final String name) {
        return jobRuns.all().stream().filter(run -> run.name().equals(name)).findFirst().orElseThrow();
    }

}
