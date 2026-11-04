package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.BlocklistStatus;
import cc.hrva.urlshortener.dto.SystemStatusResponse;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Blocklists;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Database;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Email;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Job;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Problem;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Redis;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Screenshots;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Server;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Summary;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Traffic;
import cc.hrva.urlshortener.dto.SystemStatusResponse.VirusTotal;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.SystemStatusService;
import cc.hrva.urlshortener.service.inspection.InspectionUsage;
import cc.hrva.urlshortener.service.inspection.InspectionWorker;
import cc.hrva.urlshortener.service.jobs.JobOutcome;
import cc.hrva.urlshortener.service.jobs.JobRuns;
import cc.hrva.urlshortener.service.jobs.JobSchedules;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.stats.AppVersion;
import cc.hrva.urlshortener.service.stats.RecentRequests;
import cc.hrva.urlshortener.service.stats.RuntimeMetrics;
import cc.hrva.urlshortener.service.stats.ServicePings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSystemStatusService implements SystemStatusService {

    static final long SERVER_ERRORS_FROM_REQUESTS = 20;
    static final double SERVER_ERRORS_SHARE = 0.05;
    static final double NEAR_THE_LIMIT_SHARE = 0.9;
    static final Duration LONG_PAUSE = Duration.ofMinutes(5);
    private static final Duration EMAIL_FAILURES_WINDOW = Duration.ofHours(24);
    private static final String UNKNOWN_VERSION = "unknown";
    private static final String VERSION_PREFIX = "v";
    private static final double MILLISECONDS_PER_SECOND = 1000.0;
    private static final String AT = "{at}";
    private static final String DATABASE_DOWN = "Database: not reachable.";
    private static final String DATABASE_EFFECT = "Cached links still open, but other links, signing in and saving changes fail.";
    private static final String REDIS_DOWN = "Cache (Redis): not reachable.";
    private static final String REDIS_EFFECT = "Links still open from the database, but making links and unlocking password-protected links are refused.";
    private static final String ERRORS_CONTINUE = "Some pages and links are failing right now.";
    private static final String ERRORS_STOPPED = "They have stopped.";
    private static final String SCREENSHOTS_WAIT_FOR_MIDNIGHT = "New screenshots wait until midnight UTC.";
    private static final String SCREENSHOTS_NEAR_THE_LIMIT = "At the limit, new screenshots wait until midnight UTC.";
    private static final String SCREENSHOTS_WAIT = "New screenshots wait until then.";
    private static final String LOOKUPS_WAIT_FOR_MIDNIGHT = "New lookups wait until midnight UTC.";
    private static final String LOOKUPS_WAIT = "New lookups wait until then.";
    private static final String OLD_COPY = "Links are checked against an old copy of it.";
    private static final String NOT_CHECKED = "Links aren't checked against it.";

    private final RecentRequests recentRequests;
    private final ServicePings servicePings;
    private final RuntimeMetrics runtimeMetrics;
    private final AppVersion appVersion;
    private final EmailLogRepository emailLogRepository;
    private final BlocklistSourceService blocklistSourceService;
    private final InspectionUsage inspectionUsage;
    private final InspectionWorker inspectionWorker;
    private final SettingsService settingsService;
    private final JobRuns jobRuns;
    private final JobSchedules jobSchedules;
    private final AppProperties appProperties;
    private final Clock clock;

    @Override
    public SystemStatusResponse status() {
        final var database = database();
        final var redis = redis();
        final var traffic = traffic();
        final var email = email(database.up());
        final var screenshots = screenshots(database.up(), redis.up());
        final var virusTotal = virusTotal(database.up(), redis.up());
        final var blocklistStatuses = blocklistStatuses(database.up(), redis.up());
        final var jobs = jobs();
        final var problems = new ArrayList<Problem>();
        if (!database.up()) {
            problems.add(new Problem(DATABASE_DOWN, null, DATABASE_EFFECT));
        }
        if (!redis.up()) {
            problems.add(new Problem(REDIS_DOWN, null, REDIS_EFFECT));
        }
        serverErrorsProblem(traffic).ifPresent(problems::add);
        screenshotsProblem(screenshots).ifPresent(problems::add);
        virusTotalProblem(virusTotal).ifPresent(problems::add);
        blocklistStatuses.stream().filter(BlocklistStatus::stale).map(DefaultSystemStatusService::staleProblem).forEach(problems::add);
        emailProblem(email).ifPresent(problems::add);
        jobs.stream().filter(job -> job.lastOutcome() == JobOutcome.FAILED).map(DefaultSystemStatusService::jobProblem).forEach(problems::add);
        final var sentences = problems.stream().map(DefaultSystemStatusService::sentence).toList();
        return new SystemStatusResponse(new Summary(problems.isEmpty(), sentences, List.copyOf(problems)), traffic, database, redis, email, screenshots,
                virusTotal, blocklists(blocklistStatuses), server(), jobs);
    }

    private static String sentence(final Problem problem) {
        final var text = problem.at() == null ? problem.text() : problem.text().replace(AT, readable(problem.at()));
        return problem.detail() == null ? text : text + " " + problem.detail();
    }

    private Database database() {
        final var ping = servicePings.database();
        final var pool = servicePings.databasePool();
        return new Database(ping.up(), ping.latencyMs(), pool.active(), pool.max());
    }

    private Redis redis() {
        final var ping = servicePings.redis();
        return new Redis(ping.up(), ping.latencyMs(), runtimeMetrics.urlCacheStats().hitRatio());
    }

    private Traffic traffic() {
        final var window = recentRequests.window();
        return new Traffic(window.windowMinutes(), window.requests(), window.redirects(), window.p50Ms(), window.p95Ms(), window.serverErrors(),
                window.recentMinutes(), window.recentServerErrors());
    }

    private Email email(final boolean databaseUp) {
        if (!databaseUp) {
            return new Email(null, 0);
        }
        try {
            final var since = LocalDateTime.now(clock).minus(EMAIL_FAILURES_WINDOW);
            return new Email(emailLogRepository.findLastSentAt().orElse(null),
                    emailLogRepository.countByStatusAndCreatedAtGreaterThanEqual(EmailLog.STATUS_FAILED, since));
        } catch (final RuntimeException exception) {
            log.warn("Could not read the email log for the system status exception={}", exception.getClass().getName());
            return new Email(null, 0);
        }
    }

    private Screenshots screenshots(final boolean databaseUp, final boolean redisUp) {
        final var cloudflare = appProperties.getCloudflare();
        final var configured = StringUtils.isNotBlank(cloudflare.getAccountId()) && StringUtils.isNotBlank(cloudflare.getBrowserApiToken());
        return new Screenshots(configured, settingsService.isOn(SettingKey.LINK_SCREENSHOTS),
                redisUp ? valueOf(inspectionUsage.browserSecondsToday()) : null,
                appProperties.getInspection().getBrowserSecondsDailyLimit(),
                databaseUp ? valueOf(inspectionUsage.screenshotsQueued()) : null,
                utc(inspectionWorker.screenshotsPausedUntil()));
    }

    private VirusTotal virusTotal(final boolean databaseUp, final boolean redisUp) {
        return new VirusTotal(StringUtils.isNotBlank(appProperties.getInspection().getVirusTotalApiKey()), settingsService.isOn(SettingKey.VIRUSTOTAL_LOOKUPS),
                redisUp ? valueOf(inspectionUsage.virusTotalLookupsToday()) : null,
                appProperties.getInspection().getVirusTotalDailyLimit(),
                databaseUp ? valueOf(inspectionUsage.virusTotalQueued()) : null,
                utc(inspectionWorker.virusTotalPausedUntil()));
    }

    private List<BlocklistStatus> blocklistStatuses(final boolean databaseUp, final boolean redisUp) {
        if (!databaseUp) {
            return List.of();
        }
        try {
            return redisUp ? blocklistSourceService.health() : blocklistSourceService.healthFromDatabase();
        } catch (final RuntimeException exception) {
            log.warn("Could not read the blocklists for the system status exception={}", exception.getClass().getName());
            return List.of();
        }
    }

    private static Blocklists blocklists(final List<BlocklistStatus> statuses) {
        final var lastRefreshAt = statuses.stream()
                .map(BlocklistStatus::lastRefreshedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new Blocklists(lastRefreshAt, statuses.stream().filter(BlocklistStatus::stale).map(BlocklistStatus::name).toList());
    }

    private Server server() {
        final var heap = runtimeMetrics.heap();
        final var version = appVersion.current();
        return new Server(UNKNOWN_VERSION.equals(version) ? null : VERSION_PREFIX + version,
                LocalDateTime.ofInstant(runtimeMetrics.startedAt(), ZoneOffset.UTC),
                runtimeMetrics.uptime().toSeconds(),
                runtimeMetrics.startup().map(startup -> startup.toMillis() / MILLISECONDS_PER_SECOND).orElse(null),
                heap.usedBytes(),
                heap.maxBytes(),
                valueOf(runtimeMetrics.gcPauseMillis()));
    }

    private List<Job> jobs() {
        final var schedules = jobSchedules.all();
        return jobRuns.all().stream()
                .map(run -> {
                    final var schedule = schedules.get(run.name());
                    return new Job(run.name(), run.label(), run.lastStartedAt(), run.lastFinishedAt(), run.lastOutcome(), run.lastError(), run.lastReason(),
                            run.internal(), schedule == null ? null : schedule.text(), schedule == null ? null : schedule.nextRunAt());
                })
                .toList();
    }

    private static Optional<Problem> serverErrorsProblem(final Traffic traffic) {
        if (traffic.requests() < SERVER_ERRORS_FROM_REQUESTS || traffic.serverErrors() <= traffic.requests() * SERVER_ERRORS_SHARE) {
            return Optional.empty();
        }
        final var hour = "Server errors: %d of %d requests in the last %d minutes, ".formatted(traffic.serverErrors(), traffic.requests(),
                traffic.windowMinutes());
        if (traffic.recentServerErrors() == 0) {
            return Optional.of(new Problem(hour + "none in the last %d minutes.".formatted(traffic.recentMinutes()), null, ERRORS_STOPPED));
        }
        return Optional.of(new Problem(hour + "%d in the last %d minutes.".formatted(traffic.recentServerErrors(), traffic.recentMinutes()), null,
                ERRORS_CONTINUE));
    }

    private boolean pausedForLong(final LocalDateTime pausedUntil) {
        return pausedUntil != null && pausedUntil.isAfter(LocalDateTime.now(clock).plus(LONG_PAUSE));
    }

    private Optional<Problem> screenshotsProblem(final Screenshots screenshots) {
        if (!screenshots.configured() || !screenshots.enabled()) {
            return Optional.empty();
        }
        final var used = screenshots.browserSecondsToday();
        final var limit = screenshots.browserSecondsLimit();
        if (used != null && used >= limit) {
            return Optional.of(new Problem("Screenshots: today's limit is used up.", null, SCREENSHOTS_WAIT_FOR_MIDNIGHT));
        }
        if (pausedForLong(screenshots.pausedUntil())) {
            return Optional.of(new Problem("Screenshots: paused until " + AT + ".", screenshots.pausedUntil(), SCREENSHOTS_WAIT));
        }
        if (used != null && used >= limit * NEAR_THE_LIMIT_SHARE) {
            return Optional.of(new Problem(String.format(Locale.ROOT, "Screenshots: %d of %d seconds of browser time used today.", (long) Math.floor(used),
                    limit), null, SCREENSHOTS_NEAR_THE_LIMIT));
        }
        return Optional.empty();
    }

    private Optional<Problem> virusTotalProblem(final VirusTotal virusTotal) {
        if (!virusTotal.configured() || !virusTotal.enabled()) {
            return Optional.empty();
        }
        if (virusTotal.lookupsToday() != null && virusTotal.lookupsToday() >= virusTotal.dailyLimit()) {
            return Optional.of(new Problem("VirusTotal: today's %d lookups are used up.".formatted(virusTotal.dailyLimit()), null, LOOKUPS_WAIT_FOR_MIDNIGHT));
        }
        if (pausedForLong(virusTotal.pausedUntil())) {
            return Optional.of(new Problem("VirusTotal: paused until " + AT + ".", virusTotal.pausedUntil(), LOOKUPS_WAIT));
        }
        return Optional.empty();
    }

    private static Problem staleProblem(final BlocklistStatus status) {
        return status.lastRefreshedAt() == null
                ? new Problem("Blocklists: " + status.name() + " is stale, never refreshed.", null, NOT_CHECKED)
                : new Problem("Blocklists: " + status.name() + " is stale, last refreshed " + AT + ".", status.lastRefreshedAt(), OLD_COPY);
    }

    private static Optional<Problem> emailProblem(final Email email) {
        if (email.failures24h() == 0) {
            return Optional.empty();
        }
        final var one = email.failures24h() == 1;
        return Optional.of(new Problem("Email: %d %s in the last 24 hours.".formatted(email.failures24h(), one ? "failure" : "failures"), null,
                one ? "That email wasn't delivered." : "Those emails weren't delivered."));
    }

    private static Problem jobProblem(final Job job) {
        return new Problem("Background jobs: " + job.label() + " failed at " + AT + ".", Objects.requireNonNullElse(job.lastFinishedAt(), job.lastStartedAt()),
                job.lastReason());
    }

    private static String readable(final LocalDateTime at) {
        return at.format(DefaultSendingEmailService.LONG_DATE_TIME_UTC_FORMAT);
    }

    private static LocalDateTime utc(final Optional<Instant> instant) {
        return instant.map(at -> LocalDateTime.ofInstant(at, ZoneOffset.UTC)).orElse(null);
    }

    private static Long valueOf(final OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
    }

    private static Double valueOf(final OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }

}
