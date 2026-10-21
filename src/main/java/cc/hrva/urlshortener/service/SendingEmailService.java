package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.DecisionsDigest;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;

public interface SendingEmailService {

    void sendWelcomeEmail(User user);
    void sendVerificationEmail(User user, cc.hrva.urlshortener.model.VerificationToken token);
    void sendNewUserNotificationToAdmin(User user);
    void sendEmailUrlMalwareDetected(User user, Url url, String threatType);
    void sendEmailUrlBlocked(User owner, Url url);
    void sendEmailAccountDeactivated(User user);
    void sendEmailForgotPassword(User user, ResetToken resetToken);
    void sendDecisionsEmailToAdmin(DecisionsDigest digest);
    EmailLog resendEmail(Long emailLogId);
    void failInterruptedEmails();
    void forgetOldEmailContent();

}
