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
        assertThat(runs.getFirst()).isEqualTo(new JobRun("DefaultScheduledService.deactivateExpiredUrls", "Turn off expired links", false, null, null, null, null, null));
        assertThat(runs).extracting(JobRun::name).containsExactlyElementsOf(JobLabels.all().keySet());
    }

    @Test
    void aRunningJobHasAStartAfterItsLastFinish() {
        jobRuns.started("DefaultScheduledService.deactivateExpiredUrls");
        clock.advance(Duration.ofMillis(250));
        jobRuns.finished("DefaultScheduledService.deactivateExpiredUrls");
        clock.advance(Duration.ofMinutes(1));
        jobRuns.started("DefaultScheduledService.deactivateExpiredUrls");

        assertThat(jobRuns.all().getFirst()).isEqualTo(new JobRun("DefaultScheduledService.deactivateExpiredUrls", "Turn off expired links", false,
                NOW.plusMinutes(1).plusNanos(250_000_000), NOW.plusNanos(250_000_000), JobOutcome.OK, null, null));
    }

    @Test
    void aFailedRunKeepsItsErrorUntilTheNextFailure() {
        jobRuns.started("DefaultScheduledService.refreshDueBlocklists");
        jobRuns.failed("DefaultScheduledService.refreshDueBlocklists", new org.springframework.data.redis.RedisConnectionFailureException("Redis is down"));

        assertThat(run("DefaultScheduledService.refreshDueBlocklists")).isEqualTo(new JobRun("DefaultScheduledService.refreshDueBlocklists",
                "Refresh due blocklists", false, NOW, NOW, JobOutcome.FAILED, "org.springframework.data.redis.RedisConnectionFailureException: Redis is down",
                "Redis wasn't reachable."));

        clock.advance(Duration.ofHours(1));
        jobRuns.started("DefaultScheduledService.refreshDueBlocklists");
        jobRuns.finished("DefaultScheduledService.refreshDueBlocklists");

        assertThat(run("DefaultScheduledService.refreshDueBlocklists").lastOutcome()).isEqualTo(JobOutcome.OK);
        assertThat(run("DefaultScheduledService.refreshDueBlocklists").lastFinishedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(run("DefaultScheduledService.refreshDueBlocklists").lastError())
                .isEqualTo("org.springframework.data.redis.RedisConnectionFailureException: Redis is down");
        assertThat(run("DefaultScheduledService.refreshDueBlocklists").lastReason()).isEqualTo("Redis wasn't reachable.");
    }

    @Test
    void aJobWithoutALabelComesLastUnderItsMethodName() {
        jobRuns.started("zeta");
        jobRuns.started("alpha");
        jobRuns.finished("alpha");

        final var runs = jobRuns.all();

        assertThat(runs).hasSize(JobLabels.all().size() + 2);
        assertThat(runs.get(runs.size() - 2)).isEqualTo(new JobRun("alpha", "alpha", false, NOW, NOW, JobOutcome.OK, null, null));
        assertThat(runs.getLast()).isEqualTo(new JobRun("zeta", "zeta", false, NOW, null, null, null, null));
    }

    @Test
    void anInternalJobSaysSo() {
        assertThat(run("InspectionGauges.refreshInspectionGauges").internal()).isTrue();
        assertThat(run("DefaultScheduledService.deactivateExpiredUrls").internal()).isFalse();
    }

    @Test
    void theGaugesNameTheJobByItsLabelAndKeepTheClassAndMethodAsTheJobTag() {
        jobRuns.bindTo(registry);

        assertThat(registry.get(JobRuns.LAST_OUTCOME).tag("job", "DefaultDiscordAlertService.flush").tag("label", "Post Discord alerts").gauge()).isNotNull();
        assertThat(registry.get(JobRuns.LAST_RUN).tag("job", "DefaultSettingsService.refresh").tag("label", "Reload the settings").gauge()).isNotNull();
        assertThat(registry.get(JobRuns.LAST_OUTCOME).tag("job", "DefaultClickCheckService.reload").tag("label", "Reload the click-time blocklists").gauge())
                .isNotNull();
    }

    @Test
    void eachJobHasAnOutcomeAndALastRunGauge() {
        jobRuns.bindTo(registry);

        assertThat(registry.get(JobRuns.LAST_OUTCOME).tag("job", "DefaultScheduledService.deactivateExpiredUrls").gauge().value()).isNaN();
        assertThat(registry.get(JobRuns.LAST_RUN).tag("job", "DefaultScheduledService.deactivateExpiredUrls").gauge().value()).isNaN();
        assertThat(registry.get(JobRuns.LAST_RUN).tag("job", "DefaultScheduledService.deactivateExpiredUrls").gauge().getId().getBaseUnit()).isEqualTo("seconds");
        assertThat(registry.get(JobRuns.LAST_OUTCOME).gauges()).hasSize(JobLabels.all().size());

        jobRuns.started("DefaultScheduledService.deactivateExpiredUrls");
        jobRuns.finished("DefaultScheduledService.deactivateExpiredUrls");
        jobRuns.started("DefaultScheduledService.refreshDueBlocklists");
        jobRuns.failed("DefaultScheduledService.refreshDueBlocklists", new IllegalStateException("down"));

        assertThat(registry.get(JobRuns.LAST_OUTCOME).tag("job", "DefaultScheduledService.deactivateExpiredUrls").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(JobRuns.LAST_OUTCOME).tag("job", "DefaultScheduledService.refreshDueBlocklists").gauge().value()).isZero();
        assertThat(registry.get(JobRuns.LAST_RUN).tag("job", "DefaultScheduledService.refreshDueBlocklists").gauge().value())
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
