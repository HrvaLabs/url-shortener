package cc.hrva.urlshortener.service.jobs;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobRuns implements MeterBinder {

    public static final String LAST_OUTCOME = "hrva.job.last.outcome";
    public static final String LAST_RUN = "hrva.job.last.run.timestamp";
    private static final String JOB_TAG = "job";
    private static final String LABEL_TAG = "label";
    private static final String SECONDS = "seconds";

    private final Clock clock;
    private final Map<String, JobRun> runs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> withGauges = new ConcurrentHashMap<>();
    private volatile MeterRegistry registry;

    @Override
    public void bindTo(final MeterRegistry meterRegistry) {
        registry = meterRegistry;
        Stream.concat(JobLabels.all().keySet().stream(), runs.keySet().stream()).forEach(this::registerGauges);
    }

    public void started(final String name) {
        final var at = LocalDateTime.now(clock);
        runs.compute(name, (key, run) -> (run == null ? JobRun.neverRun(key) : run).started(at));
        registerGauges(name);
    }

    public void finished(final String name) {
        final var at = LocalDateTime.now(clock);
        runs.compute(name, (key, run) -> (run == null ? JobRun.neverRun(key) : run).finished(at));
    }

    public void failed(final String name, final Throwable throwable) {
        final var at = LocalDateTime.now(clock);
        final var error = JobErrors.describe(throwable);
        final var reason = JobReasons.of(throwable);
        runs.compute(name, (key, run) -> (run == null ? JobRun.neverRun(key) : run).failed(at, error, reason));
    }

    public List<JobRun> all() {
        final var labelled = JobLabels.all().keySet().stream().map(this::runOf);
        final var unlabelled = runs.values().stream()
                .filter(run -> !JobLabels.all().containsKey(run.name()))
                .sorted(Comparator.comparing(JobRun::name));
        return Stream.concat(labelled, unlabelled).toList();
    }

    private JobRun runOf(final String name) {
        return runs.getOrDefault(name, JobRun.neverRun(name));
    }

    private void registerGauges(final String name) {
        final var meterRegistry = registry;
        if (meterRegistry == null || withGauges.putIfAbsent(name, Boolean.TRUE) != null) {
            return;
        }
        final var label = JobLabels.labelOf(name);
        Gauge.builder(LAST_OUTCOME, this, jobRuns -> jobRuns.outcomeValue(name)).tag(JOB_TAG, name).tag(LABEL_TAG, label)
                .description("Last outcome of a scheduled job: 1 OK, 0 failed, NaN not run since the start").register(meterRegistry);
        Gauge.builder(LAST_RUN, this, jobRuns -> jobRuns.lastRunValue(name)).tag(JOB_TAG, name).tag(LABEL_TAG, label).baseUnit(SECONDS)
                .description("When a scheduled job last started, in seconds since the epoch").register(meterRegistry);
    }

    private double outcomeValue(final String name) {
        final var run = runs.get(name);
        if (run == null || run.lastOutcome() == null) {
            return Double.NaN;
        }
        return run.lastOutcome() == JobOutcome.OK ? 1 : 0;
    }

    private double lastRunValue(final String name) {
        final var run = runs.get(name);
        if (run == null || run.lastStartedAt() == null) {
            return Double.NaN;
        }
        return run.lastStartedAt().toEpochSecond(ZoneOffset.UTC);
    }

}
