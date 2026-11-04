package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.BlocklistStatus;
import cc.hrva.urlshortener.dto.SystemStatusResponse;
import cc.hrva.urlshortener.dto.stats.CacheStats;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.inspection.InspectionUsage;
import cc.hrva.urlshortener.service.inspection.InspectionWorker;
import cc.hrva.urlshortener.service.jobs.JobOutcome;
import cc.hrva.urlshortener.service.jobs.JobRuns;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import cc.hrva.urlshortener.service.stats.AppVersion;
import cc.hrva.urlshortener.service.stats.RecentRequests;
import cc.hrva.urlshortener.service.stats.RuntimeMetrics;
import cc.hrva.urlshortener.service.stats.ServicePings;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultSystemStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T22:31:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 11, 3, 22, 31);
    private static final LocalDateTime MIDNIGHT = LocalDateTime.of(2026, 11, 4, 0, 0);

    private final MutableClock clock = new MutableClock(NOW);
    private final RecentRequests recentRequests = mock(RecentRequests.class);
    private final ServicePings servicePings = mock(ServicePings.class);
    private final RuntimeMetrics runtimeMetrics = mock(RuntimeMetrics.class);
    private final AppVersion appVersion = mock(AppVersion.class);
    private final EmailLogRepository emailLogRepository = mock(EmailLogRepository.class);
    private final BlocklistSourceService blocklistSourceService = mock(BlocklistSourceService.class);
    private final InspectionUsage inspectionUsage = mock(InspectionUsage.class);
    private final InspectionWorker inspectionWorker = mock(InspectionWorker.class);
    private final AppProperties appProperties = new AppProperties();
    private final SettingsService settingsService = SettingsServiceFixtures.defaults(appProperties);
    private final JobRuns jobRuns = new JobRuns(clock);
    private final DefaultSystemStatusService service = new DefaultSystemStatusService(recentRequests, servicePings, runtimeMetrics, appVersion,
            emailLogRepository, blocklistSourceService, inspectionUsage, inspectionWorker, settingsService, jobRuns, appProperties, clock);

    @BeforeEach
    void everythingWorks() {
        appProperties.getCloudflare().setAccountId("account-1");
        appProperties.getCloudflare().setBrowserApiToken("token-1");
        appProperties.getInspection().setVirusTotalApiKey("vt-key-1");
        SettingsServiceFixtures.turnOn(settingsService, SettingKey.LINK_SCREENSHOTS);
        SettingsServiceFixtures.turnOn(settingsService, SettingKey.VIRUSTOTAL_LOOKUPS);
        traffic(80, 30, 0);
        when(servicePings.database()).thenReturn(new ServicePings.Ping(true, 1.2));
        when(servicePings.databasePool()).thenReturn(new ServicePings.Pool(1, 10));
        when(servicePings.redis()).thenReturn(new ServicePings.Ping(true, 0.8));
        when(runtimeMetrics.urlCacheStats()).thenReturn(CacheStats.of(99, 1));
        when(runtimeMetrics.heap()).thenReturn(new RuntimeMetrics.JvmHeap(430_000_000L, 1_200_000_000L));
        when(runtimeMetrics.startedAt()).thenReturn(Instant.parse("2026-11-03T21:28:06.250Z"));
        when(runtimeMetrics.uptime()).thenReturn(Duration.ofSeconds(3_774));
        when(runtimeMetrics.startup()).thenReturn(Optional.of(Duration.ofMillis(25_512)));
        when(runtimeMetrics.gcPauseMillis()).thenReturn(OptionalDouble.of(184.0));
        when(appVersion.current()).thenReturn("2.30");
        when(emailLogRepository.findLastSentAt()).thenReturn(Optional.of(LocalDateTime.of(2026, 11, 3, 15, 25)));
        when(emailLogRepository.countByStatusAndCreatedAtGreaterThanEqual(EmailLog.STATUS_FAILED, NOW_UTC.minusHours(24))).thenReturn(0L);
        when(blocklistSourceService.health()).thenReturn(List.of(
                new BlocklistStatus(1, "URLhaus", 6, LocalDateTime.of(2026, 11, 3, 21, 0), 0, false),
                new BlocklistStatus(2, "Phishing Army", 6, LocalDateTime.of(2026, 11, 3, 18, 0), 0, false)));
        when(inspectionUsage.browserSecondsToday()).thenReturn(OptionalDouble.of(42.0));
        when(inspectionUsage.screenshotsQueued()).thenReturn(OptionalLong.of(0));
        when(inspectionUsage.virusTotalLookupsToday()).thenReturn(OptionalLong.of(3));
        when(inspectionUsage.virusTotalQueued()).thenReturn(OptionalLong.of(1));
        when(inspectionWorker.screenshotsPausedUntil()).thenReturn(Optional.empty());
        when(inspectionWorker.virusTotalPausedUntil()).thenReturn(Optional.empty());
    }

    @Test
    void whenEverythingWorksThereAreNoProblems() {
        final var status = service.status();

        assertThat(status.summary()).isEqualTo(new SystemStatusResponse.Summary(true, List.of()));
        assertThat(status.traffic()).isEqualTo(new SystemStatusResponse.Traffic(60, 80, 30, 4.0, 120.0, 0));
        assertThat(status.database()).isEqualTo(new SystemStatusResponse.Database(true, 1.2, 1, 10));
        assertThat(status.redis()).isEqualTo(new SystemStatusResponse.Redis(true, 0.8, 99.0));
        assertThat(status.email()).isEqualTo(new SystemStatusResponse.Email(LocalDateTime.of(2026, 11, 3, 15, 25), 0));
        assertThat(status.screenshots()).isEqualTo(new SystemStatusResponse.Screenshots(true, true, 42.0, 600, 0L, null));
        assertThat(status.virusTotal()).isEqualTo(new SystemStatusResponse.VirusTotal(true, true, 3L, 480, 1L, null));
        assertThat(status.blocklists()).isEqualTo(new SystemStatusResponse.Blocklists(LocalDateTime.of(2026, 11, 3, 21, 0), List.of()));
        assertThat(status.server()).isEqualTo(new SystemStatusResponse.Server("v2.30", LocalDateTime.of(2026, 11, 3, 21, 28, 6, 250_000_000), 3_774L,
                25.512, 430_000_000L, 1_200_000_000L, 184.0));
        assertThat(status.jobs()).hasSize(jobRuns.all().size());
        assertThat(status.jobs().getFirst()).isEqualTo(new SystemStatusResponse.Job("deactivateExpiredUrls", "Turn off expired links", null, null, null, null));
    }

    @Test
    void theDatabaseDown() {
        when(servicePings.database()).thenReturn(new ServicePings.Ping(false, null));

        final var status = service.status();

        assertThat(status.summary()).isEqualTo(new SystemStatusResponse.Summary(false, List.of("Database: not reachable.")));
        assertThat(status.database().up()).isFalse();
        assertThat(status.database().latencyMs()).isNull();
        assertThat(status.email()).isEqualTo(new SystemStatusResponse.Email(null, 0));
        assertThat(status.screenshots().queued()).isNull();
        assertThat(status.virusTotal().queued()).isNull();
        assertThat(status.blocklists()).isEqualTo(new SystemStatusResponse.Blocklists(null, List.of()));
        verifyNoInteractions(emailLogRepository, blocklistSourceService);
    }

    @Test
    void redisDown() {
        when(servicePings.redis()).thenReturn(new ServicePings.Ping(false, null));

        final var status = service.status();

        assertThat(status.summary().problems()).containsExactly("Redis: not reachable.");
        assertThat(status.redis()).isEqualTo(new SystemStatusResponse.Redis(false, null, 99.0));
        assertThat(status.screenshots().browserSecondsToday()).isNull();
        assertThat(status.virusTotal().lookupsToday()).isNull();
    }

    @Test
    void serverErrorsAboveFivePercentOfAtLeastTwentyRequests() {
        traffic(80, 30, 5);

        assertThat(service.status().summary().problems()).containsExactly("Server errors: 5 of 80 requests in the last 60 minutes.");

        traffic(80, 30, 4);
        assertThat(service.status().summary().ok()).isTrue();

        traffic(19, 0, 19);
        assertThat(service.status().summary().ok()).isTrue();

        traffic(20, 0, 2);
        assertThat(service.status().summary().problems()).containsExactly("Server errors: 2 of 20 requests in the last 60 minutes.");
    }

    @Test
    void aFailedJobNamesItsLabelAndWhenItFailed() {
        jobRuns.started("refreshDueBlocklists");
        clock.advance(Duration.ofSeconds(22));
        jobRuns.failed("refreshDueBlocklists", new IllegalStateException("Redis is down"));
        jobRuns.started("deactivateExpiredUrls");
        jobRuns.finished("deactivateExpiredUrls");

        final var status = service.status();

        assertThat(status.summary().problems()).containsExactly("Refresh due blocklists failed at 3 November 2026 at 22:31 UTC.");
        assertThat(status.jobs()).filteredOn(job -> job.name().equals("refreshDueBlocklists")).singleElement()
                .isEqualTo(new SystemStatusResponse.Job("refreshDueBlocklists", "Refresh due blocklists", NOW_UTC, NOW_UTC.plusSeconds(22),
                        JobOutcome.FAILED, "java.lang.IllegalStateException: Redis is down"));
    }

    @Test
    void screenshotsWithTodaysLimitUsedUp() {
        when(inspectionUsage.browserSecondsToday()).thenReturn(OptionalDouble.of(600.4));
        when(inspectionWorker.screenshotsPausedUntil()).thenReturn(Optional.of(Instant.parse("2026-11-04T00:00:00Z")));

        final var status = service.status();

        assertThat(status.summary().problems()).containsExactly("Screenshots: today's limit is used up.");
        assertThat(status.screenshots().pausedUntil()).isEqualTo(MIDNIGHT);
    }

    @Test
    void screenshotsOverNinetyPercentOfTheLimit() {
        when(inspectionUsage.browserSecondsToday()).thenReturn(OptionalDouble.of(561.7));

        assertThat(service.status().summary().problems()).containsExactly("Screenshots: 561 of 600 seconds of browser time used today.");

        when(inspectionUsage.browserSecondsToday()).thenReturn(OptionalDouble.of(539.9));
        assertThat(service.status().summary().ok()).isTrue();
    }

    @Test
    void screenshotsPaused() {
        when(inspectionWorker.screenshotsPausedUntil()).thenReturn(Optional.of(Instant.parse("2026-11-03T23:31:00Z")));

        assertThat(service.status().summary().problems()).containsExactly("Screenshots: paused until 3 November 2026 at 23:31 UTC.");
    }

    @Test
    void virusTotalAtTheLimit() {
        when(inspectionUsage.virusTotalLookupsToday()).thenReturn(OptionalLong.of(480));
        when(inspectionWorker.virusTotalPausedUntil()).thenReturn(Optional.of(Instant.parse("2026-11-04T00:00:00Z")));

        final var status = service.status();

        assertThat(status.summary().problems()).containsExactly("VirusTotal: today's 480 lookups are used up.");
        assertThat(status.virusTotal().pausedUntil()).isEqualTo(MIDNIGHT);
    }

    @Test
    void virusTotalPaused() {
        when(inspectionWorker.virusTotalPausedUntil()).thenReturn(Optional.of(Instant.parse("2026-11-03T22:32:00Z")));

        assertThat(service.status().summary().problems()).containsExactly("VirusTotal: paused until 3 November 2026 at 22:32 UTC.");
    }

    @Test
    void aServiceThatIsNotSetUpOrTurnedOffIsNoProblem() {
        when(inspectionUsage.browserSecondsToday()).thenReturn(OptionalDouble.of(600));
        when(inspectionUsage.virusTotalLookupsToday()).thenReturn(OptionalLong.of(480));
        appProperties.getCloudflare().setBrowserApiToken(" ");
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.VIRUSTOTAL_LOOKUPS);

        final var status = service.status();

        assertThat(status.summary().ok()).isTrue();
        assertThat(status.screenshots().configured()).isFalse();
        assertThat(status.screenshots().enabled()).isTrue();
        assertThat(status.virusTotal().configured()).isTrue();
        assertThat(status.virusTotal().enabled()).isFalse();
    }

    @Test
    void staleBlocklists() {
        when(blocklistSourceService.health()).thenReturn(List.of(
                new BlocklistStatus(1, "URLhaus", 6, LocalDateTime.of(2026, 11, 3, 9, 0), 2, true),
                new BlocklistStatus(2, "Phishing Army", 6, null, 3, true),
                new BlocklistStatus(3, "OpenPhish", 6, LocalDateTime.of(2026, 11, 3, 21, 0), 0, false)));

        final var status = service.status();

        assertThat(status.summary().problems()).containsExactly(
                "Blocklist URLhaus is stale: last refreshed 3 November 2026 at 09:00 UTC.",
                "Blocklist Phishing Army is stale: never refreshed.");
        assertThat(status.blocklists()).isEqualTo(new SystemStatusResponse.Blocklists(LocalDateTime.of(2026, 11, 3, 21, 0),
                List.of("URLhaus", "Phishing Army")));
    }

    @Test
    void emailFailuresInTheLast24Hours() {
        when(emailLogRepository.countByStatusAndCreatedAtGreaterThanEqual(EmailLog.STATUS_FAILED, NOW_UTC.minusHours(24))).thenReturn(1L);

        assertThat(service.status().summary().problems()).containsExactly("Email: 1 failure in the last 24 hours.");

        when(emailLogRepository.countByStatusAndCreatedAtGreaterThanEqual(EmailLog.STATUS_FAILED, NOW_UTC.minusHours(24))).thenReturn(3L);
        assertThat(service.status().summary().problems()).containsExactly("Email: 3 failures in the last 24 hours.");
    }

    @Test
    void everyProblemAtOnceInAFixedOrder() {
        when(servicePings.redis()).thenReturn(new ServicePings.Ping(false, null));
        traffic(100, 10, 50);
        when(inspectionWorker.screenshotsPausedUntil()).thenReturn(Optional.of(Instant.parse("2026-11-03T23:31:00Z")));
        when(inspectionWorker.virusTotalPausedUntil()).thenReturn(Optional.of(Instant.parse("2026-11-03T22:32:00Z")));
        when(blocklistSourceService.health()).thenReturn(List.of(new BlocklistStatus(2, "Phishing Army", 6, null, 3, true)));
        when(emailLogRepository.countByStatusAndCreatedAtGreaterThanEqual(EmailLog.STATUS_FAILED, NOW_UTC.minusHours(24))).thenReturn(2L);
        jobRuns.started("checkTraffic");
        jobRuns.failed("checkTraffic", new IllegalStateException("x"));

        final var status = service.status();

        assertThat(status.summary().ok()).isFalse();
        assertThat(status.summary().problems()).containsExactly(
                "Redis: not reachable.",
                "Server errors: 50 of 100 requests in the last 60 minutes.",
                "Screenshots: paused until 3 November 2026 at 23:31 UTC.",
                "VirusTotal: paused until 3 November 2026 at 22:32 UTC.",
                "Blocklist Phishing Army is stale: never refreshed.",
                "Email: 2 failures in the last 24 hours.",
                "Check traffic for alerts failed at 3 November 2026 at 22:31 UTC.");
    }

    @Test
    void unknownValuesAreNull() {
        when(appVersion.current()).thenReturn("unknown");
        when(runtimeMetrics.startup()).thenReturn(Optional.empty());
        when(runtimeMetrics.gcPauseMillis()).thenReturn(OptionalDouble.empty());
        when(runtimeMetrics.urlCacheStats()).thenReturn(CacheStats.of(0, 0));
        when(servicePings.databasePool()).thenReturn(new ServicePings.Pool(null, null));
        when(emailLogRepository.findLastSentAt()).thenReturn(Optional.empty());
        when(inspectionUsage.screenshotsQueued()).thenReturn(OptionalLong.empty());
        when(inspectionUsage.browserSecondsToday()).thenReturn(OptionalDouble.empty());
        when(recentRequests.window()).thenReturn(new RecentRequests.Window(60, 0, 0, 0, null, null));

        final var status = service.status();

        assertThat(status.summary().ok()).isTrue();
        assertThat(status.server().version()).isNull();
        assertThat(status.server().startupSeconds()).isNull();
        assertThat(status.server().gcPauseMsTotal()).isNull();
        assertThat(status.redis().hitRatio()).isNull();
        assertThat(status.database().poolActive()).isNull();
        assertThat(status.email().lastSentAt()).isNull();
        assertThat(status.screenshots().queued()).isNull();
        assertThat(status.screenshots().browserSecondsToday()).isNull();
        assertThat(status.traffic().p50Ms()).isNull();
    }

    @Test
    void aFailingEmailLogOrBlocklistReadIsSoft() {
        when(emailLogRepository.findLastSentAt()).thenThrow(new IllegalStateException("query timeout"));
        when(blocklistSourceService.health()).thenThrow(new IllegalStateException("query timeout"));

        final var status = service.status();

        assertThat(status.email()).isEqualTo(new SystemStatusResponse.Email(null, 0));
        assertThat(status.blocklists()).isEqualTo(new SystemStatusResponse.Blocklists(null, List.of()));
    }

    private void traffic(final long requests, final long redirects, final long serverErrors) {
        when(recentRequests.window()).thenReturn(new RecentRequests.Window(60, requests, redirects, serverErrors, 4.0, 120.0));
    }

}
