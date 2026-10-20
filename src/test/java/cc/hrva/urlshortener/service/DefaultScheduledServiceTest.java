package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.service.impl.DefaultScheduledService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private IPAddressService ipAddressService;

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

    @BeforeEach
    void setUp() {
        this.scheduledService = new DefaultScheduledService(urlService, userService, apiKeyService, ipAddressService, resetTokenService, urlNotificationService, safeBrowsingRecheckService, sendingEmailService, linkDigestService);
    }

    @Test
    void shouldSendLinkDigest() {
        scheduledService.sendLinkDigest();
        verify(linkDigestService).sendDailyDigest();
    }

    @Test
    void shouldFailInterruptedEmails() {
        scheduledService.failInterruptedEmails();
        verify(sendingEmailService).failInterruptedEmails();
    }

    @Test
    void shouldCleanupIpAddresses() {
        scheduledService.cleanupIpAddresses();
        verify(ipAddressService).deactivateDeprecatedIps();
        verify(ipAddressService).deleteDeactivatedIps();
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
}
