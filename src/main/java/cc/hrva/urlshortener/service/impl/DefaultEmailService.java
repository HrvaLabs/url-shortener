package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.exception.MailingException;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import java.io.File;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.service.EmailService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultEmailService implements EmailService {

    private final JavaMailSender javaMailSender;

    @Override
    public void sendEmail(final Email email, final File attachment) throws MessagingException {
        final var mimeMessage = javaMailSender.createMimeMessage();
        final var message = new MimeMessageHelper(mimeMessage, true, "UTF-8");

        if (attachment != null) {
            try {
                attachFileToEmail(attachment, mimeMessage);
            } catch (final IOException | MessagingException e) {
                log.error("Exception while attaching file to email.", e);
                throw new MailingException("Error while attaching file to email.");
            }
        }

        setEmailMessageParameters(email, message);

        try {
            javaMailSender.send(mimeMessage);
        } catch (final Exception e) {
            log.error("Exception while sending email.", e);
            throw new MailingException("Email couldn't be sent");
        }
    }

    private void setEmailMessageParameters(final Email email, final MimeMessageHelper message) throws MessagingException {
        if (StringUtils.isNotEmpty(email.getSender())) {
            message.setFrom(email.getSender());
        }
        message.setTo(email.getReceivers());
        if (email.getBcc() != null) {
            message.setBcc(email.getBcc());
        }
        message.setSubject(email.getSubject());
        if (email.getPlainText() != null) {
            message.setText(email.getPlainText(), email.getText());
        } else {
            message.setText(email.getText(), true);
        }
    }

    public void attachFileToEmail(final File attachment, final MimeMessage mimeMessage) throws IOException, MessagingException {
        final var attachmentPart = new MimeBodyPart();
        final var multipart = (Multipart) mimeMessage.getContent();

        attachmentPart.setFileName(attachment.getName());
        attachmentPart.attachFile(attachment);
        multipart.addBodyPart(attachmentPart);
        mimeMessage.setContent(multipart);
    }

}
