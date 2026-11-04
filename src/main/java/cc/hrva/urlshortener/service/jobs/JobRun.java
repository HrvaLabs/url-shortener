package cc.hrva.urlshortener.service.jobs;

import java.time.LocalDateTime;

public record JobRun(String name, String label, LocalDateTime lastStartedAt, LocalDateTime lastFinishedAt, JobOutcome lastOutcome, String lastError) {

    static JobRun neverRun(final String name) {
        return new JobRun(name, JobLabels.labelOf(name), null, null, null, null);
    }

    JobRun started(final LocalDateTime at) {
        return new JobRun(name, label, at, lastFinishedAt, lastOutcome, lastError);
    }

    JobRun finished(final LocalDateTime at) {
        return new JobRun(name, label, lastStartedAt, at, JobOutcome.OK, lastError);
    }

    JobRun failed(final LocalDateTime at, final String error) {
        return new JobRun(name, label, lastStartedAt, at, JobOutcome.FAILED, error);
    }

}
