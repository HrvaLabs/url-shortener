package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.service.impl.DefaultEmailService;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultEmailServiceTest {

    private static final String SUBJECT = "hrva.cc weekly summary: 19–25 October 2026";
    private static final String PLAIN_TEXT = SUBJECT + "\n\nClicks: 1,204 (up 12% on the week before)\n1. hrva.cc/menu → example.com, 120 clicks";
    private static final String HTML = "<h2>" + SUBJECT + "</h2><p>Clicks: 1,204 (up 12% on the week before)</p>";

    @Mock
    private JavaMailSender javaMailSender;

    private DefaultEmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new DefaultEmailService(javaMailSender);
        when(javaMailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    @Test
    void sendsPlainTextAndHtmlAsAlternatives() throws Exception {
        emailService.sendEmail(email(PLAIN_TEXT), null);

        final var parts = partsOf(sentMessage());
        final var alternatives = parts.stream().filter(part -> typeOf(part).startsWith("multipart/alternative")).toList();
        assertThat(alternatives).hasSize(1);
        final var alternative = (Multipart) alternatives.getFirst().getContent();
        assertThat(alternative.getCount()).isEqualTo(2);
        assertThat(typeOf(alternative.getBodyPart(0))).startsWith("text/plain");
        assertThat(alternative.getBodyPart(0).getContent()).isEqualTo(PLAIN_TEXT);
        assertThat(typeOf(alternative.getBodyPart(1))).startsWith("text/html");
        assertThat(alternative.getBodyPart(1).getContent()).isEqualTo(HTML);
        assertThat(parts.stream().filter(part -> typeOf(part).startsWith("text/"))).hasSize(2);
    }

    @Test
    void anEmailWithoutPlainTextIsHtmlOnlyAsBefore() throws Exception {
        emailService.sendEmail(email(null), null);

        final var parts = partsOf(sentMessage());
        assertThat(parts).noneMatch(part -> typeOf(part).startsWith("multipart/alternative"));
        assertThat(parts).noneMatch(part -> typeOf(part).startsWith("text/plain"));
        final var html = parts.stream().filter(part -> typeOf(part).startsWith("text/html")).toList();
        assertThat(html).hasSize(1);
        assertThat(html.getFirst().getContent()).isEqualTo(HTML);
    }

    private static Email email(final String plainText) {
        return Email.builder()
                .sender("noreply@hrva.cc")
                .receivers(new String[]{"support@hrva.cc"})
                .subject(SUBJECT)
                .plainText(plainText)
                .text(HTML)
                .build();
    }

    private MimeMessage sentMessage() {
        final var sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(sent.capture());
        return sent.getValue();
    }

    private static List<Part> partsOf(final Part part) throws Exception {
        final var parts = new ArrayList<Part>(List.of(part));
        if (part.getContent() instanceof Multipart multipart) {
            for (var index = 0; index < multipart.getCount(); index++) {
                parts.addAll(partsOf(multipart.getBodyPart(index)));
            }
        }
        return parts;
    }

    private static String typeOf(final Part part) {
        try {
            return part.getDataHandler().getContentType().toLowerCase(Locale.ROOT);
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

}
