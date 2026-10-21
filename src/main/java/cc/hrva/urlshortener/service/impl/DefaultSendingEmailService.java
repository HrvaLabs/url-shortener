package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkDigestEntry;
import cc.hrva.urlshortener.exception.EmailLogNotFoundException;
import cc.hrva.urlshortener.exception.EmailNotResendableException;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import jakarta.mail.MessagingException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.SendingEmailService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultSendingEmailService implements SendingEmailService {

    private static final String RECIPIENT_SEPARATOR = ", ";
    private static final Duration INTERRUPTED_AFTER = Duration.ofMinutes(10);
    private static final Duration CONTENT_RETENTION = Duration.ofDays(7);

    private final AppProperties appProperties;
    private final TemplateEngine templateEngine;
    private final DefaultEmailService emailService;
    private final EmailLogRepository emailLogRepository;

    @Override
    @Async
    public void sendEmailForgotPassword(final User user, final ResetToken resetToken) {
        final var ctx = getContext(user);
        ctx.setVariable("request_date", resetToken.getCreateDate().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_TIME));
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
    public void sendWelcomeEmail(final User user) {
        final var ctx = getContext(user);

        final var htmlContent = templateEngine.process("welcome", ctx);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{user.getEmail()})
                .subject("Welcome")
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
    public void sendLinkDigestToAdmin(final List<LinkDigestEntry> entries) {
        final var rows = entries.stream()
                .map(entry -> """
                        <tr>
                          <td>%s/%s</td>
                          <td>%s</td>
                          <td>%s</td>
                          <td>%s</td>
                        </tr>
                        """.formatted(
                        HtmlUtils.htmlEscape(StringUtils.removeEnd(StringUtils.defaultString(appProperties.getServerUrl()), "/")),
                        HtmlUtils.htmlEscape(entry.shortUrl()),
                        HtmlUtils.htmlEscape(entry.longUrl()),
                        entry.ownerEmail() != null ? HtmlUtils.htmlEscape(entry.ownerEmail()) : "anonymous",
                        HtmlUtils.htmlEscape(String.join("; ", entry.notes()))))
                .collect(Collectors.joining());
        final var body = """
                <p>These links were created on %s in the last 24 hours and may need a look.</p>
                <table border="1" cellpadding="6" cellspacing="0">
                  <tr><th>Short link</th><th>Destination</th><th>Owner</th><th>Why</th></tr>
                %s</table>
                <p>Deactivate anything unwanted in the admin URL list.</p>
                """.formatted(HtmlUtils.htmlEscape(StringUtils.defaultString(appProperties.getAppName())), rows);

        final var email = Email.builder()
                .sender(appProperties.getEmailSenderAddress())
                .receivers(new String[]{appProperties.getContactEmail()})
                .subject(entries.size() == 1 ? "1 new link to review" : "%d new links to review".formatted(entries.size()))
                .text(body)
                .build();

        tryToSendEmail(email);
    }

    @Override
    public EmailLog resendEmail(final Long emailLogId) {
        final var failed = emailLogRepository.findById(emailLogId)
                .orElseThrow(() -> new EmailLogNotFoundException("Email log entry doesn't exist"));

        if (!failed.isResendable()) {
            throw new EmailNotResendableException("Only failed emails whose content was kept can be resent");
        }

        final var email = Email.builder()
                .sender(failed.getSender())
                .receivers(failed.getRecipient().split(RECIPIENT_SEPARATOR))
                .subject(failed.getSubject())
                .text(failed.getBody())
                .build();

        log.info("Resending email log id={}", emailLogId);
        final var resent = tryToSendEmail(email);

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

    public EmailLog tryToSendEmail(final Email email) {
        final var logEntry = emailLogRepository.save(EmailLog.builder()
                .sender(email.getSender())
                .recipient(String.join(RECIPIENT_SEPARATOR, email.getReceivers()))
                .subject(email.getSubject())
                .body(email.getText())
                .status(EmailLog.STATUS_SENDING)
                .build());

        log.info("Sending {} email to {}", email.getSubject(), Arrays.toString(email.getReceivers()));
        sendEmailWithRetries(email, logEntry);
        return logEntry;
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

    private Context getContext(final User user) {
        final var ctx = new Context();
        ctx.setVariable("name", user.getEmail());
        ctx.setVariable("number_of_api_keys", user.getApiKeySlots().toString());
        ctx.setVariable("app_name", appProperties.getAppName());
        ctx.setVariable("contact_email", appProperties.getContactEmail());
        ctx.setVariable("login_page", MessageFormat.format("{0}/login/", appProperties.getFrontendUrl()));
        ctx.setVariable("api_documentation", MessageFormat.format("{0}/swagger-ui/index.html", appProperties.getServerUrl()));
        ctx.setVariable("days_of_inactivity", appProperties.getDeactivateUserAccountAfterDays().toString());
        ctx.setVariable("password_reset_link", MessageFormat.format("{0}/reset-password", appProperties.getFrontendUrl()));
        ctx.setVariable("token_expiration", appProperties.getResetTokenExpirationInHours().toString());

        return ctx;
    }

}
