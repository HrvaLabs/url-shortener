package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.service.impl.DefaultScheduledService;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DefaultScheduledServiceTest {

    private ScheduledService scheduledService;

    @Mock
    private UrlService urlService;

    @Mock
    private UserService userService;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private ResetTokenService resetTokenService;

    @Mock
    private UrlNotificationService urlNotificationService;

    @Mock
    private SafeBrowsingRecheckService safeBrowsingRecheckService;

    @Mock
    private SendingEmailService sendingEmailService;

    @Mock
    private LinkDigestService linkDigestService;

    @Mock
    private BlocklistSourceService blocklistSourceService;

    @Mock
    private WeeklyDigestService weeklyDigestService;

    @BeforeEach
    void setUp() {
        this.scheduledService = new DefaultScheduledService(urlService, userService, apiKeyService, resetTokenService, urlNotificationService, safeBrowsingRecheckService, sendingEmailService, linkDigestService, blocklistSourceService, weeklyDigestService);
    }

    @Test
    void shouldSendWeeklyDigest() {
        scheduledService.sendWeeklyDigest();
        verify(weeklyDigestService).sendWeeklyDigest();
    }

    @Test
    void theWeeklyDigestRunsMondaysAtEightInTheAnalyticsZone() throws NoSuchMethodException {
        final var scheduled = DefaultScheduledService.class.getMethod("sendWeeklyDigest").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 8 * * MON");
        assertThat(scheduled.zone()).isEqualTo("${app.analytics.zone}");
    }

    @Test
    void theWeeklyDigestFiresAtEightInZagrebOnEitherSideOfTheClockChange() throws NoSuchMethodException, IOException {
        final var scheduled = DefaultScheduledService.class.getMethod("sendWeeklyDigest").getAnnotation(Scheduled.class);
        final var sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).forEach(sources::addLast);
        final var zone = ZoneId.of(new PropertySourcesPropertyResolver(sources).resolveRequiredPlaceholders(scheduled.zone()));
        final var cron = CronExpression.parse(scheduled.cron());

        assertThat(zone).isEqualTo(ZoneId.of("Europe/Zagreb"));
        assertThat(nextRun(cron, "2026-10-25T12:00:00Z", zone)).isEqualTo("2026-10-26T07:00:00Z");
        assertThat(nextRun(cron, "2026-10-26T07:00:00Z", zone)).isEqualTo("2026-11-02T07:00:00Z");
        assertThat(nextRun(cron, "2026-03-29T12:00:00Z", zone)).isEqualTo("2026-03-30T06:00:00Z");
        assertThat(nextRun(cron, "2026-03-27T12:00:00Z", zone)).isEqualTo("2026-03-30T06:00:00Z");
    }

    private static Instant nextRun(final CronExpression cron, final String after, final ZoneId zone) {
        return cron.next(ZonedDateTime.ofInstant(Instant.parse(after), zone)).toInstant();
    }

    @Test
    void shouldForgetOldEmailContent() {
        scheduledService.forgetOldEmailContent();
        verify(sendingEmailService).forgetOldEmailContent();
    }

    @Test
    void shouldSendLinkDigest() {
        scheduledService.sendLinkDigest();
        verify(linkDigestService).sendDailyDigest();
    }

    @Test
    void shouldRefreshDueBlocklists() {
        scheduledService.refreshDueBlocklists();
        verify(blocklistSourceService).refreshDue();
    }

    @Test
    void shouldReloadMissingBlocklists() {
        scheduledService.reloadMissingBlocklists();
        verify(blocklistSourceService).reloadMissing();
    }

    @Test
    void shouldFailInterruptedEmails() {
        scheduledService.failInterruptedEmails();
        verify(sendingEmailService).failInterruptedEmails();
    }

    @Test
    void shouldCleanupExpiredPasswordResetTokens() {
        scheduledService.cleanupExpiredPasswordResetTokens();
        verify(resetTokenService).deactivateExpiredPasswordResetTokens();
        verify(resetTokenService).deleteExpiredPasswordResetTokens();
    }

    @Test
    void shouldDeactivateUnusedUserAccounts() {
        scheduledService.deactivateUnusedUserAccounts();
        verify(userService).deactivateUnusedUserAccounts();
    }

    @Test
    void shouldDeactivateExpiredApiKeys() {
        scheduledService.deactivateExpiredApiKeys();
        verify(apiKeyService).deactivateExpired();
    }

    @Test
    void shouldDeactivateExpiredUrls() {
        scheduledService.deactivateExpiredUrls();
        verify(urlService).deactivateExpiredUrls();
    }

    @Test
    void shouldNotifyExpiringUrls() {
        scheduledService.notifyExpiringUrls();
        verify(urlNotificationService).notifyExpiringUrls();
    }

    @Test
    void shouldRecheckUrlsSafeBrowsing() {
        scheduledService.recheckUrlsSafeBrowsing();
        verify(safeBrowsingRecheckService).recheckActiveUrls();
    }

    @Test
    void theNightlyScanRunsAtHalfPastFourAfterTheFourOClockListRefresh() throws NoSuchMethodException {
        final var scheduled = DefaultScheduledService.class.getMethod("recheckUrlsSafeBrowsing").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 30 4 * * *");
    }
}
