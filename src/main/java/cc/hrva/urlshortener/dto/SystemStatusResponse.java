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
            @Schema(description = "Plain sentences, one per problem, with any time in UTC; the same problems as items") List<String> problems,
            @Schema(description = "The problems for a page to show: each text, the time its {at} stands for, and a second sentence") List<Problem> items) {
    }

    public record Problem(
            @Schema(description = "The sentence; contains {at} exactly when at is set") String text,
            @Schema(description = "The time {at} in the text stands for, in UTC") LocalDateTime at,
            @Schema(description = "A short second sentence: what stops working, or why it failed; null when there is none") String detail) {
    }

    public record Traffic(int windowMinutes, long requests, long redirects, Double p50Ms, Double p95Ms, long serverErrors,
                          @Schema(description = "The short window recentServerErrors counts, in minutes") int recentMinutes,
                          @Schema(description = "Server errors in the last recentMinutes, to tell whether errors are still happening") long recentServerErrors) {
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

    public record Job(
            @Schema(description = "The scheduled method as Class.method") String name,
            String label,
            LocalDateTime lastStartedAt,
            LocalDateTime lastFinishedAt,
            JobOutcome lastOutcome,
            @Schema(description = "The exception class and message of the last failure") String lastError,
            @Schema(description = "The last failure in plain words, such as The database wasn't reachable.; null when unknown") String lastReason,
            @Schema(description = "True for plumbing that keeps in-memory copies and metrics in step") boolean internal,
            @Schema(description = "How often it runs, such as Every 5 minutes or Daily; null when unknown") String schedule,
            @Schema(description = "When it runs next, in UTC; null when unknown") LocalDateTime nextRunAt) {
    }

}
