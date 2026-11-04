package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.BlocklistStatus;
import cc.hrva.urlshortener.dto.SystemStatusResponse;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Blocklists;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Database;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Email;
import cc.hrva.urlshortener.dto.SystemStatusResponse.Job;
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
    private static final Duration EMAIL_FAILURES_WINDOW = Duration.ofHours(24);
    private static final String UNKNOWN_VERSION = "unknown";
    private static final String VERSION_PREFIX = "v";
    private static final double MILLISECONDS_PER_SECOND = 1000.0;

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
        final var blocklistStatuses = blocklistStatuses(database.up());
        final var jobs = jobs();
        final var problems = new ArrayList<String>();
        if (!database.up()) {
            problems.add("Database: not reachable.");
        }
        if (!redis.up()) {
            problems.add("Redis: not reachable.");
        }
        serverErrorsProblem(traffic).ifPresent(problems::add);
        screenshotsProblem(screenshots).ifPresent(problems::add);
        virusTotalProblem(virusTotal).ifPresent(problems::add);
        blocklistStatuses.stream().filter(BlocklistStatus::stale).map(DefaultSystemStatusService::staleProblem).forEach(problems::add);
        emailProblem(email).ifPresent(problems::add);
        jobs.stream().filter(job -> job.lastOutcome() == JobOutcome.FAILED).map(DefaultSystemStatusService::jobProblem).forEach(problems::add);
        return new SystemStatusResponse(new Summary(problems.isEmpty(), List.copyOf(problems)), traffic, database, redis, email, screenshots, virusTotal,
                blocklists(blocklistStatuses), server(), jobs);
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
        return new Traffic(window.windowMinutes(), window.requests(), window.redirects(), window.p50Ms(), window.p95Ms(), window.serverErrors());
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

    private List<BlocklistStatus> blocklistStatuses(final boolean databaseUp) {
        if (!databaseUp) {
            return List.of();
        }
        try {
            return blocklistSourceService.health();
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
        return jobRuns.all().stream()
                .map(run -> new Job(run.name(), run.label(), run.lastStartedAt(), run.lastFinishedAt(), run.lastOutcome(), run.lastError()))
                .toList();
    }

    private static Optional<String> serverErrorsProblem(final Traffic traffic) {
        if (traffic.requests() < SERVER_ERRORS_FROM_REQUESTS || traffic.serverErrors() <= traffic.requests() * SERVER_ERRORS_SHARE) {
            return Optional.empty();
        }
        return Optional.of("Server errors: %d of %d requests in the last %d minutes.".formatted(traffic.serverErrors(), traffic.requests(),
                traffic.windowMinutes()));
    }

    private static Optional<String> screenshotsProblem(final Screenshots screenshots) {
        if (!screenshots.configured() || !screenshots.enabled()) {
            return Optional.empty();
        }
        final var used = screenshots.browserSecondsToday();
        final var limit = screenshots.browserSecondsLimit();
        if (used != null && used >= limit) {
            return Optional.of("Screenshots: today's limit is used up.");
        }
        if (screenshots.pausedUntil() != null) {
            return Optional.of("Screenshots: paused until " + readable(screenshots.pausedUntil()) + ".");
        }
        if (used != null && used >= limit * NEAR_THE_LIMIT_SHARE) {
            return Optional.of(String.format(Locale.ROOT, "Screenshots: %d of %d seconds of browser time used today.", (long) Math.floor(used), limit));
        }
        return Optional.empty();
    }

    private static Optional<String> virusTotalProblem(final VirusTotal virusTotal) {
        if (!virusTotal.configured() || !virusTotal.enabled()) {
            return Optional.empty();
        }
        if (virusTotal.lookupsToday() != null && virusTotal.lookupsToday() >= virusTotal.dailyLimit()) {
            return Optional.of("VirusTotal: today's %d lookups are used up.".formatted(virusTotal.dailyLimit()));
        }
        if (virusTotal.pausedUntil() != null) {
            return Optional.of("VirusTotal: paused until " + readable(virusTotal.pausedUntil()) + ".");
        }
        return Optional.empty();
    }

    private static String staleProblem(final BlocklistStatus status) {
        return status.lastRefreshedAt() == null
                ? "Blocklist " + status.name() + " is stale: never refreshed."
                : "Blocklist " + status.name() + " is stale: last refreshed " + readable(status.lastRefreshedAt()) + ".";
    }

    private static Optional<String> emailProblem(final Email email) {
        if (email.failures24h() == 0) {
            return Optional.empty();
        }
        return Optional.of("Email: %d %s in the last 24 hours.".formatted(email.failures24h(), email.failures24h() == 1 ? "failure" : "failures"));
    }

    private static String jobProblem(final Job job) {
        return job.label() + " failed at " + readable(Objects.requireNonNullElse(job.lastFinishedAt(), job.lastStartedAt())) + ".";
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
