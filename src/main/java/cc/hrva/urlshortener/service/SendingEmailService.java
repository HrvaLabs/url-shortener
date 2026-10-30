package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.DecisionsDigest;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.domain.DnsRecord;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.notification.DeadLinksEmail;
import cc.hrva.urlshortener.dto.notification.WeeklySummary;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import java.time.LocalDateTime;
import java.util.List;

public interface SendingEmailService {

    void sendVerificationEmail(User user, cc.hrva.urlshortener.model.VerificationToken token);
    void sendNewUserNotificationToAdmin(User user);
    void sendEmailUrlMalwareDetected(User user, Url url, String threatType);
    void sendEmailUrlBlocked(User owner, Url url);
    void sendEmailUrlsBlocked(User owner, List<Url> urls);
    void sendEmailAccountDeactivated(User user);
    void sendEmailForgotPassword(User user, ResetToken resetToken);
    void sendPasskeyAddedEmail(User user, String label, LocalDateTime addedAt);
    void sendDecisionsEmailToAdmin(DecisionsDigest digest);
    void sendWeeklyDigestToAdmin(WeeklyDigest digest);
    void sendDeadLinksEmail(DeadLinksEmail email);
    void sendWeeklySummaryEmail(WeeklySummary summary);
    void sendDomainApprovedEmail(String to, String hostname, DnsRecord record);
    void sendDomainRejectedEmail(String to, String hostname, String note);
    void sendDomainDisabledEmail(String to, String hostname, String reasonText);
    void sendDomainRemovedEmail(String to, String hostname, DomainRemovalResponse summary, DomainLinkMode mode);
    EmailLog resendEmail(Long emailLogId);
    void failInterruptedEmails();
    void forgetOldEmailContent();

}
