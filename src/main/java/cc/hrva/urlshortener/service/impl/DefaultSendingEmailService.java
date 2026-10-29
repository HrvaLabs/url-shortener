package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.DecisionsDigest;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.notification.DeadLinksEmail;
import cc.hrva.urlshortener.dto.notification.WeeklySummary;
import cc.hrva.urlshortener.exception.EmailLogNotFoundException;
import cc.hrva.urlshortener.exception.EmailNotResendableException;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.service.notification.ConsentEmails;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import cc.hrva.urlshortener.service.notification.DestinationStatusWords;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import jakarta.mail.MessagingException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.digest.WeeklyDigestText;
import cc.hrva.urlshortener.util.EmailAddresses;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultSendingEmailService implements SendingEmailService {

    private static final String RECIPIENT_SEPARATOR = ", ";
    private static final String LOCAL_PROVIDER = "local";
    private static final Duration INTERRUPTED_AFTER = Duration.ofMinutes(10);
    private static final Duration CONTENT_RETENTION = Duration.ofDays(7);
    private static final int LISTED_BLOCKED_LINKS = 20;
    private static final String STATS_PAGE = "/admin/dashboard?days=7";
    private static final String REVIEW_PAGE = "/admin/review";
    private static final String DASHBOARD_PAGE = "/dashboard";
    private static final String CONSENT_WITHDRAWN = "This person has turned this email off since, so it can't be resent.";
    private static final DateTimeFormatter PASSKEY_ADDED_AT_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH);

    private final AppProperties appProperties;
    private final TemplateEngine templateEngine;
    private final DefaultEmailService emailService;
    private final EmailLogRepository emailLogRepository;
    private final EmailConsentReader emailConsentReader;
    private final ConsentEmails consentEmails;
    private final NotificationMessages notificationMessages;

    @Override
    @Async
    public void sendEmailForgotPassword(final User user, final ResetToken resetToken) {
        final var ctx = getContext(user);
        ctx.setVariable("token", resetToken.getToken());
        ctx.setVariable("full_password_reset_link", MessageFormat.format("{0}/reset-password/{1}", appProperties.getFrontendUrl(), resetToken.getToken()));

        final var htmlContent = templateEngine.process("reset_password", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{user.getEmail()})
                .subject("Password reset token")
                .text(htmlContent)
                .build();

        tryToSendEmail(email);
    }

    @Override
    @Async
    public void sendEmailAccountDeactivated(final User user) {
        final var ctx = getContext(user);

        final var htmlContent = templateEngine.process("account_deactivated", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{user.getEmail()})
                .subject("Account deactivated")
                .text(htmlContent)
                .build();

        tryToSendEmail(email);
    }

    @Override
    @Async
    public void sendVerificationEmail(final User user, final cc.hrva.urlshortener.model.VerificationToken token) {
        final var ctx = getContext(user);
        ctx.setVariable("token", token.getToken());
        ctx.setVariable("full_verification_link",
                MessageFormat.format("{0}/verify-email/{1}", appProperties.getFrontendUrl(), token.getToken()));
        ctx.setVariable("token_expiration", appProperties.getVerificationTokenExpirationInHours().toString());

        final var htmlContent = templateEngine.process("verify_email", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{user.getEmail()})
                .subject("Verify your email")
                .text(htmlContent)
                .build();

        tryToSendEmail(email);
    }

    @Override
    @Async
    public void sendNewUserNotificationToAdmin(final User user) {
        final var provider = user.getAuthProvider() != null ? user.getAuthProvider() : "local";
        final var body = """
                <p>A new user just registered on %s.</p>
                <p><strong>Email:</strong> %s</p>
                <p><strong>Auth provider:</strong> %s</p>
                <p><strong>Registered at:</strong> %s</p>
                """.formatted(
                appProperties.getAppName(),
                user.getEmail(),
                provider,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{appProperties.getContactEmail()})
                .subject("New user registered: %s".formatted(user.getEmail()))
                .text(body)
                .build();

        tryToSendEmail(email);
    }

    @Override
    @Async
    public void sendEmailUrlMalwareDetected(final User user, final Url url, final String threatType) {
        final var ctx = getContext(user);
        ctx.setVariable("shortUrl", url.getShortUrl());
        ctx.setVariable("longUrl", url.getLongUrl());
        ctx.setVariable("threatType", threatType);

        final var htmlContent = templateEngine.process("url_malware_detected", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{user.getEmail()})
                .subject("URL deactivated — malware detected")
                .text(htmlContent)
                .build();

        tryToSendEmail(email);
    }

    @Override
    @Async
    public void sendEmailUrlBlocked(final User owner, final Url url) {
        final var ctx = getContext(owner);
        ctx.setVariable("short_link", shortLink(url));

        final var htmlContent = templateEngine.process("url_blocked", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{owner.getEmail()})
                .subject("Your link was blocked as unsafe")
                .text(htmlContent)
                .build();

        tryToSendEmail(email);
    }

    @Override
    @Async
    public void sendEmailUrlsBlocked(final User owner, final List<Url> urls) {
        final var ctx = getContext(owner);
        ctx.setVariable("link_count", urls.size());
        ctx.setVariable("short_links", urls.stream().limit(LISTED_BLOCKED_LINKS).map(this::shortLink).toList());
        ctx.setVariable("more_links", Math.max(0, urls.size() - LISTED_BLOCKED_LINKS));

        final var htmlContent = templateEngine.process("urls_blocked", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{owner.getEmail()})
                .subject("Your links were blocked as unsafe")
                .text(htmlContent)
                .build();

        tryToSendEmail(email);
    }

    @Override
    @Async
    public void sendPasskeyAddedEmail(final User user, final String label, final LocalDateTime addedAt) {
        final var ctx = getContext(user);
        ctx.setVariable("label", label);
        ctx.setVariable("added_at", addedAt.format(PASSKEY_ADDED_AT_FORMAT));
        ctx.setVariable("settings_url", frontendPage("/dashboard/settings"));
        ctx.setVariable("help_url", frontendPage("/help/passkeys"));
        ctx.setVariable("local_account", isLocalAccount(user));

        final var htmlContent = templateEngine.process("passkey_added", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{user.getEmail()})
                .subject("A passkey was added to your account")
                .text(htmlContent)
                .build();

        tryToSendEmail(email);
    }

    @Override
    @Async
    public void sendDecisionsEmailToAdmin(final DecisionsDigest digest) {
        final var subject = decisionsSubject(digest.openCount() + digest.openBioPageReviews());
        final var ctx = new Context();
        ctx.setVariable("digest", digest);
        ctx.setVariable("headline", subject);
        ctx.setVariable("app_name", appProperties.getAppName());
        ctx.setVariable("short_link_host", shortLinkHost());
        ctx.setVariable("review_page", frontendPage(REVIEW_PAGE));
        ctx.setVariable("blocklists_page", frontendPage("/admin/blocklists"));

        final var htmlContent = templateEngine.process("decisions", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{appProperties.getContactEmail()})
                .subject(subject)
                .text(htmlContent)
                .build();

        tryToSendEmail(email);
    }

    @Override
    @Async
    public void sendWeeklyDigestToAdmin(final WeeklyDigest digest) {
        final var title = WeeklyDigestText.title(digest);
        final var reviewPage = frontendPage(REVIEW_PAGE);
        final var lines = WeeklyDigestText.lines(digest, shortLinkHost(), reviewPage, UnaryOperator.identity());
        final var statsPage = frontendPage(STATS_PAGE);
        final var ctx = new Context();
        ctx.setVariable("headline", title);
        ctx.setVariable("summary", WeeklyDigestText.summary(digest, reviewPage));
        ctx.setVariable("top_links", WeeklyDigestText.topLinkLines(digest, shortLinkHost(), UnaryOperator.identity()));
        ctx.setVariable("stats_page", statsPage);
        ctx.setVariable("app_name", appProperties.getAppName());

        final var htmlContent = templateEngine.process("weekly_digest", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{appProperties.getContactEmail()})
                .subject(title)
                .plainText(title + "\n\n" + String.join("\n", lines) + "\n\nStats (last 7 days): " + statsPage)
                .text(htmlContent)
                .build();

        tryToSendEmail(email);
    }

    @Override
    @Async
    public void sendDeadLinksEmail(final DeadLinksEmail deadLinks) {
        final var type = NotificationType.DEAD_LINK_EMAIL;
        final var count = deadLinks.links().size();
        final var subject = count == 1 ? "1 of your links looks broken" : "%d of your links look broken".formatted(count);
        final var message = notificationMessages.linkDead(deadLinks.links());
        final var unsubscribePage = consentEmails.unsubscribePage(deadLinks.userId(), type);
        final var ctx = new Context();
        ctx.setVariable("headline", subject);
        ctx.setVariable("links", deadLinks.links().stream().limit(NotificationMessages.MOST_LINKS).map(this::deadLinkLine).toList());
        ctx.setVariable("more_links", message.moreCount());
        ctx.setVariable("dashboard_url", deadLinks.dashboardUrl());
        ctx.setVariable("unsubscribe_page", unsubscribePage);
        ctx.setVariable("settings_page", consentEmails.settingsPage());
        ctx.setVariable("app_name", appProperties.getAppName());

        final var htmlContent = templateEngine.process("dead_links", ctx);
        final var plainLines = new ArrayList<>(message.lines());
        if (message.moreCount() > 0) {
            plainLines.add("and " + message.moreCount() + " more");
        }

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{deadLinks.email()})
                .subject(subject)
                .plainText(subject + "\n\n" + String.join("\n", plainLines) + "\n\nOpen your links: " + deadLinks.dashboardUrl()
                        + "\n\nYou get this because you turned on dead-link emails.\nTurn off dead-link emails: " + unsubscribePage)
                .text(htmlContent)
                .build();

        tryToSendConsentedEmail(email, deadLinks.userId(), type);
    }

    public record DeadLinkLine(String target, String detail) {
    }

    @Override
    @Async
    public void sendWeeklySummaryEmail(final WeeklySummary summary) {
        final var type = NotificationType.WEEKLY_SUMMARY_EMAIL;
        final var subject = "Your hrva.cc week: " + WeeklyDigestText.weekLabel(summary.from(), summary.to());
        final var sections = notificationMessages.weeklySections(summary);
        final var dashboard = notificationMessages.frontendPage(DASHBOARD_PAGE);
        final var unsubscribePage = consentEmails.unsubscribePage(summary.userId(), type);
        final var ctx = new Context();
        ctx.setVariable("headline", subject);
        ctx.setVariable("clicks", sections.clicks());
        ctx.setVariable("top_links", sections.topLinks());
        ctx.setVariable("dead_links", sections.deadLinks());
        ctx.setVariable("dead_links_more", sections.deadLinksMore());
        ctx.setVariable("ending_soon", sections.endingSoon());
        ctx.setVariable("ending_soon_more", sections.endingSoonMore());
        ctx.setVariable("top_links_heading", NotificationMessages.TOP_LINKS);
        ctx.setVariable("dead_links_heading", NotificationMessages.STOPPED_WORKING);
        ctx.setVariable("ending_soon_heading", NotificationMessages.ENDING_SOON);
        ctx.setVariable("dashboard_url", dashboard);
        ctx.setVariable("unsubscribe_page", unsubscribePage);
        ctx.setVariable("settings_page", consentEmails.settingsPage());
        ctx.setVariable("app_name", appProperties.getAppName());

        final var htmlContent = templateEngine.process("weekly_summary", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{summary.email()})
                .subject(subject)
                .plainText(subject + "\n\n" + String.join("\n", notificationMessages.weekly(summary).lines()) + "\n\nOpen your links: " + dashboard
                        + "\n\nYou get this because you turned on the weekly summary.\nTurn off the weekly summary: " + unsubscribePage)
                .text(htmlContent)
                .build();

        tryToSendConsentedEmail(email, summary.userId(), type);
    }

    @Override
    public EmailLog resendEmail(final Long emailLogId) {
        final var failed = emailLogRepository.findById(emailLogId)
                .orElseThrow(() -> new EmailLogNotFoundException("Email log entry doesn't exist"));

        if (!failed.isResendable()) {
            throw new EmailNotResendableException("Only failed emails whose content was kept can be resent");
        }

        final var consentType = failed.getConsentType();
        if (consentType != null && (failed.getUserId() == null || !hasConsent(failed.getUserId(), consentType))) {
            log.info("Resend refused, consent withdrawn email log id={} type={}", emailLogId, consentType);
            throw new EmailNotResendableException(CONSENT_WITHDRAWN);
        }

        final var email = Email.builder()
                .sender(failed.getSender())
                .receivers(failed.getRecipient().split(RECIPIENT_SEPARATOR))
                .subject(failed.getSubject())
                .text(failed.getBody())
                .headers(consentType != null ? consentEmails.headers(failed.getUserId(), consentType) : Map.of())
                .build();

        log.info("Resending email log id={}", emailLogId);
        final var resent = tryToSendEmail(email, consentType, failed.getUserId());

        failed.setStatus(EmailLog.STATUS_RESENT);
        failed.setBody(null);
        emailLogRepository.save(failed);
        return resent;
    }

    @Override
    public void forgetOldEmailContent() {
        final var old = emailLogRepository.findAllByBodyIsNotNullAndCreatedAtBefore(LocalDateTime.now().minus(CONTENT_RETENTION));
        if (old.isEmpty()) {
            return;
        }

        old.forEach(entry -> entry.setBody(null));
        emailLogRepository.saveAll(old);
        log.info("Dropped the stored content of {} old emails", old.size());
    }

    @Override
    public void failInterruptedEmails() {
        final var interrupted = emailLogRepository.findAllByStatusAndCreatedAtBefore(
                EmailLog.STATUS_SENDING, LocalDateTime.now().minus(INTERRUPTED_AFTER));

        if (interrupted.isEmpty()) {
            return;
        }

        interrupted.forEach(entry -> {
            entry.setStatus(EmailLog.STATUS_FAILED);
            entry.setErrorMessage("Sending was interrupted before it finished");
        });
        emailLogRepository.saveAll(interrupted);
        log.warn("Marked {} interrupted emails as failed", interrupted.size());
    }

    public Optional<EmailLog> tryToSendConsentedEmail(final Email email, final long userId, final NotificationType type) {
        if (!type.isEmail()) {
            throw new IllegalArgumentException("Only email notification types are sent as consented email");
        }
        if (!hasConsent(userId, type)) {
            log.info("Consented email skipped, consent withdrawn user id={} type={}", userId, type);
            return Optional.empty();
        }

        final var headers = new LinkedHashMap<>(email.getHeaders());
        headers.putAll(consentEmails.headers(userId, type));
        email.setHeaders(Collections.unmodifiableMap(headers));
        return Optional.of(tryToSendEmail(email, type, userId));
    }

    public EmailLog tryToSendEmail(final Email email) {
        return tryToSendEmail(email, null, null);
    }

    private EmailLog tryToSendEmail(final Email email, final NotificationType consentType, final Long userId) {
        final var logEntry = emailLogRepository.save(EmailLog.builder()
                .sender(email.getSender())
                .recipient(String.join(RECIPIENT_SEPARATOR, email.getReceivers()))
                .subject(email.getSubject())
                .body(email.getText())
                .status(EmailLog.STATUS_SENDING)
                .consentType(consentType)
                .userId(userId)
                .build());

        log.info("Sending {} email to {} recipient(s): {}",
                EmailAddresses.maskedIn(email.getSubject()), email.getReceivers().length, maskedRecipients(email.getReceivers()));
        sendEmailWithRetries(email, logEntry);
        return logEntry;
    }

    private boolean hasConsent(final long userId, final NotificationType type) {
        return emailConsentReader.hasActiveEmailConsent(userId, type);
    }

    private void sendEmailWithRetries(final Email email, final EmailLog logEntry) {
        int attempts = 0;
        while (attempts < 3) {
            final var exception = attemptSendEmail(email);
            if (exception.isEmpty()) {
                logEntry.setStatus(EmailLog.STATUS_SENT);
                logEntry.setSentAt(LocalDateTime.now());
                logEntry.setBody(null);
                emailLogRepository.save(logEntry);
                return;
            }

            attempts++;
            if (attempts >= 3) {
                log.error("Failed to send email after {} attempts", attempts, exception.get());
                logEntry.setStatus(EmailLog.STATUS_FAILED);
                logEntry.setErrorMessage(exception.get().getMessage());
                emailLogRepository.save(logEntry);
            } else {
                log.warn("Failed to send email (attempt {}/3), retrying...", attempts);
                if (!sleepBeforeRetry(attempts)) {
                    return;
                }
            }
        }
    }

    private Optional<Exception> attemptSendEmail(final Email email) {
        try {
            emailService.sendEmail(email, null);
            return Optional.empty();
        } catch (final MessagingException | RuntimeException e) {
            return Optional.of(e);
        }
    }

    private boolean sleepBeforeRetry(final int attemptNumber) {
        try {
            Thread.sleep((long) Math.pow(2, attemptNumber) * 100L);
            return true;
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<String> maskedRecipients(final String[] receivers) {
        return Stream.of(receivers).map(EmailAddresses::masked).toList();
    }

    private static String decisionsSubject(final long openCount) {
        if (openCount == 0) {
            return "No decisions waiting on hrva.cc, but there's news";
        }
        return openCount == 1 ? "1 decision waiting on hrva.cc" : "%d decisions waiting on hrva.cc".formatted(openCount);
    }

    private DeadLinkLine deadLinkLine(final DeadLink link) {
        return new DeadLinkLine(notificationMessages.target(link),
                "Since " + notificationMessages.dayText(link.deadSince()) + ", " + DestinationStatusWords.of(link.lastStatus()));
    }

    private String shortLinkHost() {
        final var serverUrl = StringUtils.removeEnd(StringUtils.defaultString(appProperties.getServerUrl()), "/");
        return serverUrl.contains("://") ? StringUtils.substringAfter(serverUrl, "://") : serverUrl;
    }

    private String frontendPage(final String path) {
        return StringUtils.removeEnd(StringUtils.defaultString(appProperties.getFrontendUrl()), "/") + path;
    }

    private String shortLink(final Url url) {
        return StringUtils.removeEnd(StringUtils.defaultString(appProperties.getServerUrl()), "/") + "/" + url.getShortUrl();
    }

    private static boolean isLocalAccount(final User user) {
        return user.getAuthProvider() == null || LOCAL_PROVIDER.equals(user.getAuthProvider());
    }

    private Context getContext(final User user) {
        final var ctx = new Context();
        ctx.setVariable("name", user.getEmail());
        ctx.setVariable("app_name", appProperties.getAppName());
        ctx.setVariable("contact_email", appProperties.getContactEmail());
        ctx.setVariable("login_page", MessageFormat.format("{0}/login/", appProperties.getFrontendUrl()));
        ctx.setVariable("days_of_inactivity", appProperties.getDeactivateUserAccountAfterDays().toString());
        ctx.setVariable("token_expiration", appProperties.getResetTokenExpirationInHours().toString());

        return ctx;
    }

}
