package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.service.jobs.JobOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

public record SystemStatusResponse(
        Summary summary,
        Traffic traffic,
        Database database,
        Redis redis,
        Email email,
        Screenshots screenshots,
        VirusTotal virusTotal,
        Blocklists blocklists,
        Server server,
        List<Job> jobs) {

    public record Summary(
            @Schema(description = "True when problems is empty") boolean ok,
            @Schema(description = "Plain sentences, one per problem") List<String> problems) {
    }

    public record Traffic(int windowMinutes, long requests, long redirects, Double p50Ms, Double p95Ms, long serverErrors) {
    }

    public record Database(boolean up, Double latencyMs, Integer poolActive, Integer poolMax) {
    }

    public record Redis(boolean up, Double latencyMs, @Schema(description = "Link cache hit ratio in percent; null before the first lookup") Double hitRatio) {
    }

    public record Email(LocalDateTime lastSentAt, long failures24h) {
    }

    public record Screenshots(boolean configured, boolean enabled, Double browserSecondsToday, Integer browserSecondsLimit, Long queued,
                              LocalDateTime pausedUntil) {
    }

    public record VirusTotal(boolean configured, boolean enabled, Long lookupsToday, Integer dailyLimit, Long queued, LocalDateTime pausedUntil) {
    }

    public record Blocklists(LocalDateTime lastRefreshAt, @Schema(description = "Names of the stale lists") List<String> stale) {
    }

    public record Server(String version, LocalDateTime startedAt, Long uptimeSeconds, Double startupSeconds, Long heapUsedBytes, Long heapMaxBytes,
                         Double gcPauseMsTotal) {
    }

    public record Job(String name, String label, LocalDateTime lastStartedAt, LocalDateTime lastFinishedAt, JobOutcome lastOutcome, String lastError) {
    }

}
