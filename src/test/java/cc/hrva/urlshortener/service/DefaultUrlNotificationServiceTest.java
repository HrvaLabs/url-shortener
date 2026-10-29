package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultSendingEmailService;
import cc.hrva.urlshortener.service.impl.DefaultUrlNotificationService;
import cc.hrva.urlshortener.service.notification.ConsentEmails;
import cc.hrva.urlshortener.service.notification.NotificationTestKeys;
import cc.hrva.urlshortener.service.notification.UnsubscribeTokens;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUrlNotificationServiceTest {

    private static final User ANA = User.builder().id(7L).email("ana@example.com").build();
    private static final User BOB = User.builder().id(8L).email("bob@example.com").build();

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private DefaultSendingEmailService sendingEmailService;

    @Mock
    private NotificationConsentService notificationConsentService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final UnsubscribeTokens unsubscribeTokens = new UnsubscribeTokens(NotificationTestKeys.keys());

    private DefaultUrlNotificationService service;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setAppName("hrva.cc");
        appProperties.setEmailSenderAddress("noreply@hrva.cc");
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("https://app.hrva.cc");
        appProperties.setUrlExpirationNotificationHours(24L);
        service = new DefaultUrlNotificationService(urlRepository, sendingEmailService, notificationConsentService,
                new ConsentEmails(unsubscribeTokens, appProperties), appProperties, realTemplateEngine(), transactionManager);
        lenient().when(sendingEmailService.tryToSendConsentedEmail(any(Email.class), anyLong(), eq(NotificationType.EXPIRY_REMINDER_EMAIL)))
                .thenAnswer(invocation -> Optional.of(EmailLog.builder().id(1L).build()));
    }

    @Test
    void expiryNotificationsRunOutsideAReadOnlyTransactionSoTheEmailLogCanBeWritten() throws NoSuchMethodException {
        final var transactional = DefaultUrlNotificationService.class.getMethod("notifyExpiringUrls").getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void expiryReminderNeedsConsent() {
        when(urlRepository.findByExpirationDateBetweenAndActiveTrueAndOwnerIsNotNull(any(), any()))
                .thenReturn(List.of(link(31L, "anas-menu", ANA), link(32L, "bobs-menu", BOB), link(33L, "anas-shop", ANA)));
        when(notificationConsentService.emailConsenters(NotificationType.EXPIRY_REMINDER_EMAIL, Set.of(7L, 8L))).thenReturn(Set.of(7L));

        service.notifyExpiringUrls();

        final var sent = ArgumentCaptor.forClass(Email.class);
        verify(sendingEmailService, org.mockito.Mockito.times(2)).tryToSendConsentedEmail(sent.capture(), eq(7L), eq(NotificationType.EXPIRY_REMINDER_EMAIL));
        verify(sendingEmailService, never()).tryToSendConsentedEmail(any(Email.class), eq(8L), any());
        verify(sendingEmailService, never()).tryToSendEmail(any(Email.class));
        assertThat(sent.getAllValues()).allSatisfy(email -> {
            assertThat(email.getReceivers()).containsExactly("ana@example.com");
            assertThat(email.getSubject()).isEqualTo("Your short link is expiring soon");
        });
        assertThat(sent.getAllValues()).extracting(email -> email.getText().contains("anas-menu"), email -> email.getText().contains("anas-shop"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple(true, false), org.assertj.core.groups.Tuple.tuple(false, true));
    }

    @Test
    void theLinksAndTheConsentsAreReadInOneReadOnlyTransaction() {
        when(urlRepository.findByExpirationDateBetweenAndActiveTrueAndOwnerIsNotNull(any(), any())).thenReturn(List.of(link(31L, "anas-menu", ANA)));
        when(notificationConsentService.emailConsenters(NotificationType.EXPIRY_REMINDER_EMAIL, Set.of(7L))).thenReturn(Set.of());

        service.notifyExpiringUrls();

        final var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        verify(notificationConsentService).emailConsenters(any(), any());
        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void nothingExpiringReadsNoConsents() {
        when(urlRepository.findByExpirationDateBetweenAndActiveTrueAndOwnerIsNotNull(any(), any())).thenReturn(List.of());

        service.notifyExpiringUrls();

        verifyNoInteractions(notificationConsentService, sendingEmailService);
    }

    @Test
    void theWindowIsTheNextDay() {
        final var from = ArgumentCaptor.forClass(LocalDateTime.class);
        final var to = ArgumentCaptor.forClass(LocalDateTime.class);
        when(urlRepository.findByExpirationDateBetweenAndActiveTrueAndOwnerIsNotNull(from.capture(), to.capture())).thenReturn(List.of());

        service.notifyExpiringUrls();

        assertThat(from.getValue()).isBetween(LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
        assertThat(to.getValue()).isEqualTo(from.getValue().plusHours(24));
    }

    @Test
    void reminderCarriesTheUnsubscribeLinks() {
        when(urlRepository.findByExpirationDateBetweenAndActiveTrueAndOwnerIsNotNull(any(), any())).thenReturn(List.of(link(31L, "anas-menu", ANA)));
        when(notificationConsentService.emailConsenters(NotificationType.EXPIRY_REMINDER_EMAIL, Set.of(7L))).thenReturn(Set.of(7L));

        service.notifyExpiringUrls();

        final var sent = ArgumentCaptor.forClass(Email.class);
        verify(sendingEmailService).tryToSendConsentedEmail(sent.capture(), eq(7L), eq(NotificationType.EXPIRY_REMINDER_EMAIL));
        final var html = sent.getValue().getText();
        final var token = unsubscribeTokens.issue(7L, NotificationType.EXPIRY_REMINDER_EMAIL);
        assertThat(html)
                .contains("/unsubscribe?token=")
                .contains("href=\"https://app.hrva.cc/unsubscribe?token=" + token + "\"")
                .contains("href=\"https://app.hrva.cc/dashboard/settings#notifications\"");
        assertThat(textOf(html))
                .contains("You get this because you turned on expiry reminders.")
                .contains("Turn off expiry reminders")
                .contains("Notification settings")
                .contains("2026-10-28");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theLogNamesIdsNotAddresses(final CapturedOutput output) {
        when(urlRepository.findByExpirationDateBetweenAndActiveTrueAndOwnerIsNotNull(any(), any())).thenReturn(List.of(link(31L, "anas-menu", ANA)));
        when(notificationConsentService.emailConsenters(NotificationType.EXPIRY_REMINDER_EMAIL, Set.of(7L))).thenReturn(Set.of(7L));

        service.notifyExpiringUrls();

        assertThat(output).contains("Sent expiry reminder url id=31 owner id=7").doesNotContain("ana@example.com");
    }

    private static Url link(final long id, final String shortUrl, final User owner) {
        return Url.builder().id(id).shortUrl(shortUrl).longUrl("https://example.com/" + shortUrl).owner(owner)
                .expirationDate(LocalDateTime.of(2026, 10, 28, 9, 30)).active(true).build();
    }

    private static String textOf(final String html) {
        return HtmlUtils.htmlUnescape(html.replaceAll("(?s)<head>.*</head>", "").replaceAll("<[^>]+>", "")).replaceAll("\\s+", " ").trim();
    }

    private static TemplateEngine realTemplateEngine() {
        final var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        final var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

}
