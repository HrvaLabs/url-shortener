package cc.hrva.urlshortener.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.service.ApiKeyService;
import cc.hrva.urlshortener.service.IPAddressService;
import cc.hrva.urlshortener.service.LinkDigestService;
import cc.hrva.urlshortener.service.ResetTokenService;
import cc.hrva.urlshortener.service.SafeBrowsingRecheckService;
import cc.hrva.urlshortener.service.ScheduledService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.UrlNotificationService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class DefaultScheduledService implements ScheduledService {

    private final UrlService urlService;
    private final UserService userService;
    private final ApiKeyService apiKeyService;
    private final IPAddressService ipAddressService;
    private final ResetTokenService resetTokenService;
    private final UrlNotificationService urlNotificationService;
    private final SafeBrowsingRecheckService safeBrowsingRecheckService;
    private final SendingEmailService sendingEmailService;
    private final LinkDigestService linkDigestService;

    @Override
    @Scheduled(cron = "0 */1 * * * *")
    public void cleanupIpAddresses() {
        log.info("Scheduled cleanup: IP addresses");
        ipAddressService.deactivateDeprecatedIps();
        ipAddressService.deleteDeactivatedIps();
    }

    @Override
    @Scheduled(cron = "0 */1 * * * *")
    public void deactivateExpiredUrls() {
        log.info("Scheduled cleanup: expired URLs");
        urlService.deactivateExpiredUrls();
    }

    @Override
    @Scheduled(cron = "0 0 * * * *")
    public void deactivateExpiredApiKeys() {
        log.info("Scheduled cleanup: expired API keys");
        apiKeyService.deactivateExpired();
    }

    @Override
    @Scheduled(cron = "0 0 10 * * *")
    public void deactivateUnusedUserAccounts() {
        log.info("Scheduled cleanup: unused user accounts");
        userService.deactivateUnusedUserAccounts();
    }

    @Override
    @Scheduled(cron = "0 */1 * * * *")
    public void cleanupExpiredPasswordResetTokens() {
        log.info("Scheduled cleanup: password reset tokens");
        resetTokenService.deactivateExpiredPasswordResetTokens();
        resetTokenService.deleteExpiredPasswordResetTokens();
    }

    @Override
    @Scheduled(cron = "0 0 8 * * *")
    public void notifyExpiringUrls() {
        log.info("Scheduled: expiring URL notifications");
        urlNotificationService.notifyExpiringUrls();
    }

    @Override
    @Scheduled(cron = "0 0 2 * * *")
    public void recheckUrlsSafeBrowsing() {
        log.info("Scheduled: SafeBrowsing recheck");
        safeBrowsingRecheckService.recheckActiveUrls();
    }

    @Override
    @Scheduled(cron = "0 */5 * * * *")
    public void failInterruptedEmails() {
        log.info("Scheduled cleanup: interrupted emails");
        sendingEmailService.failInterruptedEmails();
    }

    @Override
    @Scheduled(cron = "0 15 3 * * *")
    public void forgetOldEmailContent() {
        log.info("Scheduled cleanup: old email content");
        sendingEmailService.forgetOldEmailContent();
    }

    @Override
    @Scheduled(cron = "0 0 9 * * *")
    public void sendLinkDigest() {
        log.info("Scheduled: link digest");
        linkDigestService.sendDailyDigest();
    }

}
