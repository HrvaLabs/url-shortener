package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.service.impl.DefaultScheduledService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

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

    @BeforeEach
    void setUp() {
        this.scheduledService = new DefaultScheduledService(urlService, userService, apiKeyService, resetTokenService, urlNotificationService, safeBrowsingRecheckService, sendingEmailService, linkDigestService, blocklistSourceService);
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
