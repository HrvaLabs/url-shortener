package cc.hrva.urlshortener.service.jobs;

import java.time.LocalDateTime;

public record JobRun(String name, String label, boolean internal, LocalDateTime lastStartedAt, LocalDateTime lastFinishedAt, JobOutcome lastOutcome,
                     String lastError, String lastReason) {

    static JobRun neverRun(final String name) {
        return new JobRun(name, JobLabels.labelOf(name), JobLabels.isInternal(name), null, null, null, null, null);
    }

    JobRun started(final LocalDateTime at) {
        return new JobRun(name, label, internal, at, lastFinishedAt, lastOutcome, lastError, lastReason);
    }

    JobRun finished(final LocalDateTime at) {
        return new JobRun(name, label, internal, lastStartedAt, at, JobOutcome.OK, lastError, lastReason);
    }

    JobRun failed(final LocalDateTime at, final String error, final String reason) {
        return new JobRun(name, label, internal, lastStartedAt, at, JobOutcome.FAILED, error, reason);
    }

}
