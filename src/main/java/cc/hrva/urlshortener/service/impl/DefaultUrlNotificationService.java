package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.UrlNotificationService;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.notification.ConsentEmails;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultUrlNotificationService implements UrlNotificationService {

    private static final String SUBJECT = "Your short link is expiring soon";
    private static final NotificationType REMINDER = NotificationType.EXPIRY_REMINDER_EMAIL;

    private final UrlRepository urlRepository;
    private final DefaultSendingEmailService sendingEmailService;
    private final EmailConsentReader emailConsentReader;
    private final ConsentEmails consentEmails;
    private final AppProperties appProperties;
    private final TemplateEngine templateEngine;
    private final PlatformTransactionManager transactionManager;
    private final ShortLinks shortLinks;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void notifyExpiringUrls() {
        final var now = LocalDateTime.now();
        final var notificationWindow = appProperties.getUrlExpirationNotificationHours();
        final var due = expiringLinks(now, now.plusHours(notificationWindow));

        if (due.links().isEmpty()) {
            return;
        }

        final var reminded = due.links().stream().filter(link -> due.consentingOwnerIds().contains(link.ownerId())).toList();
        log.info("Found {} URLs expiring within {} hours, {} of them with expiry reminders on", due.links().size(), notificationWindow, reminded.size());

        reminded.forEach(this::sendExpirationNotification);
    }

    private ExpiringLinks expiringLinks(final LocalDateTime from, final LocalDateTime to) {
        final var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> {
            final var links = urlRepository.findByExpirationDateBetweenAndActiveTrueAndOwnerIsNotNull(from, to).stream()
                    .filter(url -> url.getOwner() != null && url.getOwner().getEmail() != null)
                    .map(ExpiringLink::of)
                    .toList();
            if (links.isEmpty()) {
                return new ExpiringLinks(List.of(), Set.of());
            }

            final var ownerIds = links.stream().map(ExpiringLink::ownerId).collect(Collectors.toSet());
            return new ExpiringLinks(links, emailConsentReader.consenters(REMINDER, ownerIds));
        });
    }

    private void sendExpirationNotification(final ExpiringLink link) {
        final var expirationDate = link.expirationDate() != null
                ? link.expirationDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : "unknown";

        final var ctx = new Context();
        ctx.setVariable("name", link.ownerEmail());
        ctx.setVariable("shortUrl", link.shortUrl());
        ctx.setVariable("short_link", shortLinks.display(link.domain(), link.shortUrl()));
        ctx.setVariable("longUrl", link.longUrl());
        ctx.setVariable("expirationDate", expirationDate);
        ctx.setVariable("app_name", appProperties.getAppName());
        ctx.setVariable("site_url", appProperties.getFrontendUrl());
        ctx.setVariable("login_page", MessageFormat.format("{0}/login/", appProperties.getFrontendUrl()));
        ctx.setVariable("unsubscribe_page", consentEmails.unsubscribePage(link.ownerId(), REMINDER));
        ctx.setVariable("settings_page", consentEmails.settingsPage());

        final var htmlContent = templateEngine.process("expiry_notification", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{link.ownerEmail()})
                .subject(SUBJECT)
                .text(htmlContent)
                .build();

        sendingEmailService.tryToSendConsentedEmail(email, link.ownerId(), REMINDER)
                .ifPresent(entry -> log.info("Sent expiry reminder url id={} owner id={}", link.urlId(), link.ownerId()));
    }

    record ExpiringLink(long urlId, String shortUrl, String longUrl, LocalDateTime expirationDate, long ownerId, String ownerEmail, String domain) {

        static ExpiringLink of(final Url url) {
            return new ExpiringLink(url.getId(), url.getShortUrl(), url.getLongUrl(), url.getExpirationDate(),
                    url.getOwner().getId(), url.getOwner().getEmail(), ShortLinks.hostnameOf(url));
        }

    }

    private record ExpiringLinks(List<ExpiringLink> links, Set<Long> consentingOwnerIds) {
    }

}
