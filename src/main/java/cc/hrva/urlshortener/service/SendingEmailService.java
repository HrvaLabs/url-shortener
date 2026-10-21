package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.LinkDigestEntry;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import java.util.List;

public interface SendingEmailService {

    void sendWelcomeEmail(User user);
    void sendVerificationEmail(User user, cc.hrva.urlshortener.model.VerificationToken token);
    void sendNewUserNotificationToAdmin(User user);
    void sendEmailUrlMalwareDetected(User user, Url url, String threatType);
    void sendEmailAccountDeactivated(User user);
    void sendEmailForgotPassword(User user, ResetToken resetToken);
    void sendLinkDigestToAdmin(List<LinkDigestEntry> entries);
    EmailLog resendEmail(Long emailLogId);
    void failInterruptedEmails();
    void forgetOldEmailContent();

}
