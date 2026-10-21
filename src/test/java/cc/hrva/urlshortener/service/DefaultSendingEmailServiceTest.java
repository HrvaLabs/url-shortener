package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkDigestEntry;
import cc.hrva.urlshortener.exception.EmailLogNotFoundException;
import cc.hrva.urlshortener.exception.EmailNotResendableException;
import cc.hrva.urlshortener.exception.MailingException;
import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.service.impl.DefaultEmailService;
import cc.hrva.urlshortener.service.impl.DefaultSendingEmailService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSendingEmailServiceTest {

    private static final User OWNER = User.builder().email("alice@example.com").apiKeySlots(4L).build();
    private static final Url BLOCKED_LINK = Url.builder().shortUrl("promo").longUrl("https://jofogas.cards/login")
            .blockReason(BlockReason.BLOCKLIST).blockDetail("HaGeZi Fake").active(false).build();

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private DefaultEmailService emailService;

    @Mock
    private EmailLogRepository emailLogRepository;

    private DefaultSendingEmailService sendingEmailService;

    private final List<String> bodiesWhileSending = new ArrayList<>();

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setEmailSenderAddress("noreply@hrva.cc");
        appProperties.setContactEmail("support@hrva.cc");
        appProperties.setAppName("hrva.cc");
        appProperties.setServerUrl("https://hrva.cc");
        sendingEmailService = new DefaultSendingEmailService(appProperties, templateEngine, emailService, emailLogRepository);
    }

    private void saveReturnsArgument() {
        when(emailLogRepository.save(any(EmailLog.class))).thenAnswer(invocation -> {
            final EmailLog entry = invocation.getArgument(0);
            if (EmailLog.STATUS_SENDING.equals(entry.getStatus())) {
                bodiesWhileSending.add(entry.getBody());
            }
            return entry;
        });
    }

    private DefaultSendingEmailService blockedLinkEmailService(final TemplateEngine engine) {
        final var appProperties = new AppProperties();
        appProperties.setEmailSenderAddress("noreply@hrva.cc");
        appProperties.setContactEmail("support@hrva.cc");
        appProperties.setAppName("hrva.cc");
        appProperties.setServerUrl("https://hrva.cc/");
        appProperties.setFrontendUrl("https://hrva.cc");
        appProperties.setDeactivateUserAccountAfterDays(180L);
        appProperties.setResetTokenExpirationInHours(1L);
        return new DefaultSendingEmailService(appProperties, engine, emailService, emailLogRepository);
    }

    private EmailLog lastSavedEntry() {
        final var captor = ArgumentCaptor.forClass(EmailLog.class);
        verify(emailLogRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void marksEmailFailedWithReasonWhenTheMailServerRejectsEveryAttempt() throws Exception {
        saveReturnsArgument();
        doThrow(new MailingException("Email couldn't be sent")).when(emailService).sendEmail(any(Email.class), eq(null));

        sendingEmailService.sendNewUserNotificationToAdmin(User.builder().email("new@example.com").build());

        verify(emailService, times(3)).sendEmail(any(Email.class), eq(null));
        final var entry = lastSavedEntry();
        assertThat(entry.getStatus()).isEqualTo(EmailLog.STATUS_FAILED);
        assertThat(entry.getErrorMessage()).isEqualTo("Email couldn't be sent");
    }

    @Test
    void keepsTheContentOfAFailedEmailSoItCanBeResent() throws Exception {
        saveReturnsArgument();
        doThrow(new MailingException("Email couldn't be sent")).when(emailService).sendEmail(any(Email.class), eq(null));

        sendingEmailService.sendNewUserNotificationToAdmin(User.builder().email("new@example.com").build());

        final var entry = lastSavedEntry();
        assertThat(entry.getSender()).isEqualTo("noreply@hrva.cc");
        assertThat(entry.getBody()).contains("new@example.com");
        assertThat(entry.isResendable()).isTrue();
    }

    @Test
    void dropsTheContentOnceTheEmailIsSent() {
        saveReturnsArgument();

        sendingEmailService.sendNewUserNotificationToAdmin(User.builder().email("new@example.com").build());

        assertThat(bodiesWhileSending).singleElement().asString().contains("new@example.com");
        final var entry = lastSavedEntry();
        assertThat(entry.getStatus()).isEqualTo(EmailLog.STATUS_SENT);
        assertThat(entry.getBody()).isNull();
        assertThat(entry.isResendable()).isFalse();
    }

    @Test
    void recordsRecipientsWithoutArrayBrackets() {
        saveReturnsArgument();

        sendingEmailService.sendNewUserNotificationToAdmin(User.builder().email("new@example.com").build());

        assertThat(lastSavedEntry().getRecipient()).isEqualTo("support@hrva.cc");
    }

    @Test
    void resendSendsTheStoredEmailAgainAndRetiresTheFailedEntry() throws Exception {
        saveReturnsArgument();
        final var failed = EmailLog.builder()
                .id(7L)
                .sender("noreply@hrva.cc")
                .recipient("a@example.com, b@example.com")
                .subject("Welcome")
                .body("<p>hello</p>")
                .status(EmailLog.STATUS_FAILED)
                .build();
        when(emailLogRepository.findById(7L)).thenReturn(Optional.of(failed));
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        final var result = sendingEmailService.resendEmail(7L);

        assertThat(sent.getValue().getSender()).isEqualTo("noreply@hrva.cc");
        assertThat(sent.getValue().getReceivers()).containsExactly("a@example.com", "b@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("Welcome");
        assertThat(sent.getValue().getText()).isEqualTo("<p>hello</p>");
        assertThat(failed.getStatus()).isEqualTo(EmailLog.STATUS_RESENT);
        assertThat(failed.getBody()).isNull();
        assertThat(result).isNotSameAs(failed);
        assertThat(result.getStatus()).isEqualTo(EmailLog.STATUS_SENT);
        assertThat(result.getRecipient()).isEqualTo("a@example.com, b@example.com");
        final var order = org.mockito.Mockito.inOrder(emailService, emailLogRepository);
        order.verify(emailService).sendEmail(any(Email.class), eq(null));
        order.verify(emailLogRepository).save(org.mockito.ArgumentMatchers.same(failed));
    }

    @Test
    void forgetsTheContentOfFailedEmailsAfterAWeek() {
        final var old = EmailLog.builder().id(12L).status(EmailLog.STATUS_FAILED).body("<p>reset link</p>").build();
        final var cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        when(emailLogRepository.findAllByBodyIsNotNullAndCreatedAtBefore(cutoff.capture())).thenReturn(List.of(old));

        sendingEmailService.forgetOldEmailContent();

        assertThat(cutoff.getValue()).isBetween(LocalDateTime.now().minusDays(7).minusMinutes(1), LocalDateTime.now().minusDays(7).plusMinutes(1));
        assertThat(old.getBody()).isNull();
        assertThat(old.isResendable()).isFalse();
        verify(emailLogRepository).saveAll(List.of(old));
    }

    @Test
    void resendRefusesAnEmailThatWasDelivered() throws Exception {
        final var delivered = EmailLog.builder().id(8L).status(EmailLog.STATUS_SENT).build();
        when(emailLogRepository.findById(8L)).thenReturn(Optional.of(delivered));

        assertThatThrownBy(() -> sendingEmailService.resendEmail(8L)).isInstanceOf(EmailNotResendableException.class);
        verify(emailService, never()).sendEmail(any(Email.class), any());
    }

    @Test
    void resendRefusesAFailedEmailWhoseContentWasNeverStored() throws Exception {
        final var legacy = EmailLog.builder().id(9L).status(EmailLog.STATUS_FAILED).build();
        when(emailLogRepository.findById(9L)).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> sendingEmailService.resendEmail(9L)).isInstanceOf(EmailNotResendableException.class);
        verify(emailService, never()).sendEmail(any(Email.class), any());
    }

    @Test
    void resendOfAnUnknownEntryIsNotFound() {
        when(emailLogRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sendingEmailService.resendEmail(10L)).isInstanceOf(EmailLogNotFoundException.class);
    }

    @Test
    void linkDigestGoesToTheAdminWithEscapedDestinations() throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        sendingEmailService.sendLinkDigestToAdmin(List.of(
                new LinkDigestEntry("evil", "https://x.example/?q=<script>", null, List.of("login or verification lure"))));

        assertThat(sent.getValue().getReceivers()).containsExactly("support@hrva.cc");
        assertThat(sent.getValue().getSubject()).isEqualTo("1 new link to review");
        assertThat(sent.getValue().getText())
                .contains("https://hrva.cc/evil")
                .contains("https://x.example/?q=&lt;script&gt;")
                .doesNotContain("<script>")
                .contains("anonymous")
                .contains("login or verification lure");
    }

    @Test
    void aBlockedLinkEmailGoesToTheOwnerWithTheShortLinkAndNothingAboutTheBlock() throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));
        final var context = ArgumentCaptor.forClass(IContext.class);
        when(templateEngine.process(eq("url_blocked"), context.capture())).thenReturn("<p>blocked</p>");

        blockedLinkEmailService(templateEngine).sendEmailUrlBlocked(OWNER, BLOCKED_LINK);

        assertThat(sent.getValue().getReceivers()).containsExactly("alice@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("Your link was blocked as unsafe");
        assertThat(sent.getValue().getText()).isEqualTo("<p>blocked</p>");
        assertThat(context.getValue().getVariable("short_link")).isEqualTo("https://hrva.cc/promo");
        assertThat(context.getValue().getVariableNames()).allSatisfy(name -> assertThat(String.valueOf(context.getValue().getVariable(name)))
                .doesNotContain("HaGeZi")
                .doesNotContain("jofogas.cards"));
    }

    @Test
    void theBlockedLinkEmailSaysWhatHappenedWithoutNamingAListOrARule() throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));
        final var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        final var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        blockedLinkEmailService(engine).sendEmailUrlBlocked(OWNER, BLOCKED_LINK);

        assertThat(sent.getValue().getText())
                .contains("hrva.cc disabled your link because it led to a site reported for phishing, malware or scams.")
                .contains("https://hrva.cc/promo")
                .contains("alice@example.com")
                .contains("support@hrva.cc")
                .doesNotContain("HaGeZi")
                .doesNotContain("jofogas.cards")
                .doesNotContain("BLOCKLIST");
    }

    @Test
    void marksEmailsStuckInSendingAsFailed() {
        final var stuck = EmailLog.builder().id(11L).status(EmailLog.STATUS_SENDING).body("<p>x</p>").build();
        when(emailLogRepository.findAllByStatusAndCreatedAtBefore(eq(EmailLog.STATUS_SENDING), any(LocalDateTime.class)))
                .thenReturn(List.of(stuck));

        sendingEmailService.failInterruptedEmails();

        assertThat(stuck.getStatus()).isEqualTo(EmailLog.STATUS_FAILED);
        assertThat(stuck.getErrorMessage()).isEqualTo("Sending was interrupted before it finished");
        assertThat(stuck.isResendable()).isTrue();
        verify(emailLogRepository).saveAll(List.of(stuck));
    }

    @Test
    void onlyTreatsEmailsAsInterruptedAfterTenMinutes() {
        final var cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        when(emailLogRepository.findAllByStatusAndCreatedAtBefore(eq(EmailLog.STATUS_SENDING), cutoff.capture())).thenReturn(List.of());

        sendingEmailService.failInterruptedEmails();

        assertThat(cutoff.getValue()).isBetween(LocalDateTime.now().minusMinutes(11), LocalDateTime.now().minusMinutes(9));
    }

}
