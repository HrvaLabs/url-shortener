package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.UrlNotificationService;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Slf4j
@Service
public class DefaultUrlNotificationService implements UrlNotificationService {

    private final UrlRepository urlRepository;
    private final DefaultSendingEmailService sendingEmailService;
    private final AppProperties appProperties;
    private final TemplateEngine templateEngine;

    public DefaultUrlNotificationService(
            final UrlRepository urlRepository,
            final DefaultSendingEmailService sendingEmailService,
            final AppProperties appProperties,
            final TemplateEngine templateEngine) {
        this.urlRepository = urlRepository;
        this.sendingEmailService = sendingEmailService;
        this.appProperties = appProperties;
        this.templateEngine = templateEngine;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void notifyExpiringUrls() {
        final var now = LocalDateTime.now();
        final var notificationWindow = appProperties.getUrlExpirationNotificationHours();
        final var deadline = now.plusHours(notificationWindow);

        final var expiringUrls = urlRepository.findByExpirationDateBetweenAndActiveTrueAndOwnerIsNotNull(now, deadline);

        if (expiringUrls.isEmpty()) {
            return;
        }

        log.info("Found {} URLs expiring within {} hours", expiringUrls.size(), notificationWindow);

        for (final var url : expiringUrls) {
            sendExpirationNotification(url);
        }
    }

    private void sendExpirationNotification(final Url url) {
        final var owner = url.getOwner();
        if (owner == null || owner.getEmail() == null) {
            return;
        }

        final var expirationDate = url.getExpirationDate() != null
                ? url.getExpirationDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : "unknown";

        final var ctx = new Context();
        ctx.setVariable("name", owner.getEmail());
        ctx.setVariable("shortUrl", url.getShortUrl());
        ctx.setVariable("longUrl", url.getLongUrl());
        ctx.setVariable("expirationDate", expirationDate);
        ctx.setVariable("app_name", appProperties.getAppName());
        ctx.setVariable("login_page", MessageFormat.format("{0}/login/", appProperties.getFrontendUrl()));

        final var htmlContent = templateEngine.process("expiry_notification", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{owner.getEmail()})
                .subject("Your short link is expiring soon")
                .text(htmlContent)
                .build();

        sendingEmailService.tryToSendEmail(email);
        log.info("Sent expiration notification for url={} to user={}", url.getShortUrl(), owner.getEmail());
    }

}
