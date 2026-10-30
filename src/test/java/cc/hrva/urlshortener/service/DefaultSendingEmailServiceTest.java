package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.DecisionsDigest;
import cc.hrva.urlshortener.dto.LinkDigestEntry;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.domain.DnsRecord;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse.DomainRename;
import cc.hrva.urlshortener.dto.notification.DeadLinksEmail;
import cc.hrva.urlshortener.dto.notification.WeeklySummary;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.exception.EmailLogNotFoundException;
import cc.hrva.urlshortener.exception.EmailNotResendableException;
import cc.hrva.urlshortener.exception.MailingException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.VerificationToken;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.service.digest.WeeklyDigestText;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.impl.DefaultEmailService;
import cc.hrva.urlshortener.service.impl.DefaultSendingEmailService;
import cc.hrva.urlshortener.service.notification.ConsentEmails;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import cc.hrva.urlshortener.service.notification.NotificationTestKeys;
import cc.hrva.urlshortener.service.notification.UnsubscribeTokens;
import cc.hrva.urlshortener.service.settings.EmailKind;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.assertj.core.groups.Tuple;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.util.HtmlUtils;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSendingEmailServiceTest {

    private static final User OWNER = User.builder().email("alice@example.com").apiKeySlots(4L).build();
    private static final Url BLOCKED_LINK = Url.builder().shortUrl("promo").longUrl("https://jofogas.cards/login")
            .blockReason(BlockReason.BLOCKLIST).blockDetail("HaGeZi Fake").active(false).build();
    private static final LocalDateTime PASSKEY_ADDED_AT = LocalDateTime.of(2026, 10, 25, 14, 5, 33);
    private static final String SITE_LINK = "https://hrva.cc";
    private static final String HRVALABS_LINK = "https://hrvalabs.net";
    private static final CustomDomain GO_EXAMPLE = CustomDomain.builder().id(7L).hostname("go.example.com").build();
    private static final String ARROW = Character.toString(0x2192);
    private static final String ARROW_LINE = " " + ARROW + " ";

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private DefaultEmailService emailService;

    @Mock
    private EmailLogRepository emailLogRepository;

    @Mock
    private EmailConsentReader emailConsentReader;

    @Mock
    private SettingsService settingsService;

    private final UnsubscribeTokens unsubscribeTokens = new UnsubscribeTokens(NotificationTestKeys.keys());

    private DefaultSendingEmailService sendingEmailService;

    private final List<String> bodiesWhileSending = new ArrayList<>();

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setEmailSenderAddress("noreply@hrva.cc");
        appProperties.setContactEmail("support@hrva.cc");
        appProperties.setAppName("hrva.cc");
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("https://app.hrva.cc");
        sendingEmailService = new DefaultSendingEmailService(appProperties, templateEngine, emailService, emailLogRepository,
                emailConsentReader, new ConsentEmails(unsubscribeTokens, appProperties),
                new NotificationMessages(appProperties, Clock.systemUTC(), new ShortLinks(appProperties)), new ShortLinks(appProperties),
                settingsService);
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

    private DefaultSendingEmailService configuredEmailService(final TemplateEngine engine) {
        final var appProperties = new AppProperties();
        appProperties.setEmailSenderAddress("noreply@hrva.cc");
        appProperties.setContactEmail("support@hrva.cc");
        appProperties.setAppName("hrva.cc");
        appProperties.setServerUrl("https://hrva.cc/");
        appProperties.setFrontendUrl("https://hrva.cc");
        appProperties.setDeactivateUserAccountAfterDays(180L);
        appProperties.setResetTokenExpirationInHours(1L);
        return new DefaultSendingEmailService(appProperties, engine, emailService, emailLogRepository,
                emailConsentReader, new ConsentEmails(unsubscribeTokens, appProperties),
                new NotificationMessages(appProperties, Clock.systemUTC(), new ShortLinks(appProperties)), new ShortLinks(appProperties),
                settingsService);
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

    private Email sentDecisionsEmail(final DecisionsDigest digest) throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        configuredEmailService(realTemplateEngine()).sendDecisionsEmailToAdmin(digest);

        return sent.getValue();
    }

    private Email sentWeeklyDigest(final WeeklyDigest digest) throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        configuredEmailService(realTemplateEngine()).sendWeeklyDigestToAdmin(digest);

        return sent.getValue();
    }

    private static WeeklyDigest weeklyDigest(final String destinationHost) {
        return new WeeklyDigest(
                LocalDate.of(2026, 10, 19),
                LocalDate.of(2026, 10, 25),
                new WeeklyDigest.Change(1204, 1075),
                950,
                301,
                new WeeklyDigest.Change(40, 42),
                List.of(new CountEntry("WEB", 30), new CountEntry("API_KEY", 6), new CountEntry("MCP", 4)),
                new WeeklyDigest.Change(3, 0),
                12,
                5,
                4,
                2,
                List.of(new CountEntry("BLOCKLIST", 2), new CountEntry("SAFE_BROWSING", 1)),
                List.of(new WeeklyDigest.TopLink("my_menu", destinationHost, 120, null)));
    }

    private static DecisionsDigest decisions(final long openCount) {
        return new DecisionsDigest(openCount, 0, List.of(), List.of(), List.of(), List.of(), List.of(), 0, List.of());
    }

    private static DecisionsDigest everything(final String userText) {
        return new DecisionsDigest(
                3,
                2,
                List.of(
                        new LinkDigestEntry(41L, "njuskaio_verify" + userText, "njuskaio.cam/a/MAUB3" + userText, "raulsado50w@gmail.com" + userText,
                                List.of("imitates njuskalo" + userText, "reported by a visitor: phishing"), null),
                        new LinkDigestEntry(42L, "v0DB3", "findfavoritedate.life", null, List.of("high-risk domain ending .life"), null)),
                List.of(new DecisionsDigest.PossibleScammer("scammer@example.com" + userText, 1, 2)),
                List.of(new DecisionsDigest.BlockedLink("promo" + userText, "www.jofogas.cards/login" + userText, "a blocklist (HaGeZi Fake" + userText + ")", null)),
                List.of(new DecisionsDigest.FailingSource("HaGeZi Fake" + userText, "HTTP 503 from every mirror" + userText)),
                List.of(new DecisionsDigest.NewAccountLink("fresh" + userText, "example.com/fresh" + userText, "newcomer@example.com" + userText, null)),
                0,
                List.of(new DecisionsDigest.BusyAnonymousLink("busy" + userText, "shop.example.com" + userText, 120)));
    }

    private Email sentPasskeyAddedEmail(final String label) throws Exception {
        return sentPasskeyAddedEmail(OWNER, label);
    }

    private Email sentPasskeyAddedEmail(final User user, final String label) throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        configuredEmailService(realTemplateEngine()).sendPasskeyAddedEmail(user, label, PASSKEY_ADDED_AT);

        return sent.getValue();
    }

    private static String textOf(final String html) {
        return HtmlUtils.htmlUnescape(html.replaceAll("(?s)<head>.*</head>", "").replaceAll("<[^>]+>", "")).replaceAll("\\s+", " ").trim();
    }

    private static String tagsRemoved(final String html) {
        return html.replaceAll("(?s)<head>.*</head>", "").replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private static List<String> linksIn(final String html) {
        return Pattern.compile("href=\"([^\"]*)\"").matcher(html).results().map(match -> match.group(1)).toList();
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
    @ExtendWith(OutputCaptureExtension.class)
    void resendSendsTheStoredEmailAgainAndRetiresTheFailedEntry(final CapturedOutput output) throws Exception {
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
        assertThat(output)
                .contains("Sending Welcome email to 2 recipient(s): [a***@example.com, b***@example.com]")
                .doesNotContain("a@example.com")
                .doesNotContain("b@example.com");
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
    void theDecisionsEmailGoesToTheAdminAndCountsTheDecisionsInItsSubject() throws Exception {
        final var email = sentDecisionsEmail(decisions(3));

        assertThat(email.getReceivers()).containsExactly("support@hrva.cc");
        assertThat(email.getSubject()).isEqualTo("3 decisions waiting on hrva.cc");
    }

    @Test
    void oneOpenEntryIsOneDecision() throws Exception {
        assertThat(sentDecisionsEmail(decisions(1)).getSubject()).isEqualTo("1 decision waiting on hrva.cc");
    }

    @Test
    void withNothingToDecideTheSubjectStillSaysThereIsNews() throws Exception {
        assertThat(sentDecisionsEmail(decisions(0)).getSubject()).isEqualTo("No decisions waiting on hrva.cc, but there's news");
    }

    @Test
    void eachEntryLinksToItsPlaceInTheReviewQueueAndNothingElseIsALink() throws Exception {
        final var body = sentDecisionsEmail(everything("")).getText();

        assertThat(linksIn(body))
                .contains("https://hrva.cc/admin/review?entry=41", "https://hrva.cc/admin/review?entry=42", "https://hrva.cc/admin/blocklists")
                .endsWith(SITE_LINK, HRVALABS_LINK)
                .filteredOn(link -> !link.equals(SITE_LINK) && !link.equals(HRVALABS_LINK))
                .allMatch(link -> link.matches("https://hrva\\.cc/admin/(review(\\?entry=\\d+)?|blocklists)"));
        assertThat(body.replaceAll("href=\"[^\"]*\"", "")).doesNotContain("://");
    }

    @Test
    void theDecisionsEmailShowsEverySection() throws Exception {
        final var body = sentDecisionsEmail(everything("")).getText();

        assertThat(body)
                .contains("hrva.cc/njuskaio_verify", "njuskaio.cam/a/MAUB3", "raulsado50w@gmail.com", "imitates njuskalo", "reported by a visitor: phishing")
                .contains("hrva.cc/v0DB3", "findfavoritedate.life", "anonymous", "high-risk domain ending .life")
                .contains("2 reported by visitors")
                .contains("scammer@example.com", "1 blocked", "2 flagged")
                .contains("hrva.cc/promo", "www.jofogas.cards/login", "a blocklist (HaGeZi Fake)")
                .contains("HaGeZi Fake", "HTTP 503 from every mirror")
                .contains("Links from accounts younger than 7 days", "hrva.cc/fresh", "example.com/fresh", "newcomer@example.com")
                .contains("Busiest anonymous links yesterday", "hrva.cc/busy", "shop.example.com: 120 clicks");
    }

    @Test
    void decisionsEmailShowsTheDomainLink() throws Exception {
        final var digest = new DecisionsDigest(2, 0,
                List.of(new LinkDigestEntry(41L, "menu", "njuskaio.cam/a", "raulsado50w@gmail.com", List.of("imitates njuskalo"), "go.example.com"),
                        new LinkDigestEntry(42L, "v0DB3", "findfavoritedate.life", null, List.of("high-risk domain ending .life"), null)),
                List.of(), List.of(new DecisionsDigest.BlockedLink("deal", "www.jofogas.cards/login", "a blocklist", "go.example.com")), List.of(),
                List.of(new DecisionsDigest.NewAccountLink("fresh", "example.com/fresh", "newcomer@example.com", "go.example.com")), 0, List.of());

        final var text = textOf(sentDecisionsEmail(digest).getText());

        assertThat(text).contains("go.example.com/menu", "hrva.cc/v0DB3", "go.example.com/deal", "go.example.com/fresh")
                .doesNotContain("hrva.cc/menu", "hrva.cc/deal", "hrva.cc/fresh");
    }

    @Test
    void malwareEmailShowsTheDomainLink() throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));
        final var onDomain = Url.builder().shortUrl("menu").longUrl("https://evil.example/login").domain(GO_EXAMPLE).active(false).build();
        final var service = configuredEmailService(realTemplateEngine());

        service.sendEmailUrlMalwareDetected(OWNER, onDomain, "SOCIAL_ENGINEERING");
        service.sendEmailUrlMalwareDetected(OWNER, BLOCKED_LINK, "MALWARE");

        assertThat(textOf(sent.getAllValues().getFirst().getText())).contains("Your short link go.example.com/menu has been automatically deactivated")
                .doesNotContain("hrva.cc/menu");
        assertThat(textOf(sent.getAllValues().get(1).getText())).contains("Your short link hrva.cc/promo has been automatically deactivated");
    }

    @Test
    void everythingUsersControlIsEscaped() throws Exception {
        final var body = sentDecisionsEmail(everything("<script>alert(1)</script>")).getText();

        assertThat(body)
                .doesNotContain("<script>")
                .contains("njuskaio_verify&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("njuskaio.cam/a/MAUB3&lt;script&gt;")
                .contains("raulsado50w@gmail.com&lt;script&gt;")
                .contains("imitates njuskalo&lt;script&gt;")
                .contains("scammer@example.com&lt;script&gt;")
                .contains("promo&lt;script&gt;")
                .contains("www.jofogas.cards/login&lt;script&gt;")
                .contains("HaGeZi Fake&lt;script&gt;")
                .contains("HTTP 503 from every mirror&lt;script&gt;")
                .contains("example.com/fresh&lt;script&gt;")
                .contains("newcomer@example.com&lt;script&gt;")
                .contains("busy&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("shop.example.com&lt;script&gt;alert(1)&lt;/script&gt;: 120 clicks");
    }

    @Test
    void aFailingSourceOnItsOwnShowsItsNameAndErrorAndLinksToTheBlocklists() throws Exception {
        final var email = sentDecisionsEmail(new DecisionsDigest(0, 0, List.of(), List.of(), List.of(),
                List.of(new DecisionsDigest.FailingSource("HaGeZi Fake", "HTTP 503 from every mirror")), List.of(), 0, List.of()));

        assertThat(email.getSubject()).isEqualTo("No decisions waiting on hrva.cc, but there's news");
        assertThat(email.getText()).contains("HaGeZi Fake", "HTTP 503 from every mirror");
        assertThat(linksIn(email.getText())).containsExactly("https://hrva.cc/admin/blocklists", SITE_LINK, HRVALABS_LINK);
    }

    @Test
    void theBusiestAnonymousLinksAreListedOneLineEach() throws Exception {
        final var email = sentDecisionsEmail(new DecisionsDigest(1, 0, List.of(), List.of(), List.of(), List.of(), List.of(), 0, List.of(
                new DecisionsDigest.BusyAnonymousLink("busy", "shop.example.com", 120),
                new DecisionsDigest.BusyAnonymousLink("quiet", "example.org", 3),
                new DecisionsDigest.BusyAnonymousLink("once", "example.net", 1))));

        assertThat(tagsRemoved(email.getText())).contains("Busiest anonymous links yesterday hrva.cc/busy &rarr; shop.example.com: 120 clicks "
                + "hrva.cc/quiet &rarr; example.org: 3 clicks hrva.cc/once &rarr; example.net: 1 click").doesNotContain("1 clicks");
        assertThat(linksIn(email.getText())).containsExactly("https://hrva.cc/admin/review", SITE_LINK, HRVALABS_LINK);
    }

    @Test
    void withoutBusyAnonymousLinksTheSectionIsLeftOut() throws Exception {
        assertThat(sentDecisionsEmail(decisions(1)).getText()).doesNotContain("Busiest anonymous links yesterday");
    }

    @Test
    void decisionsEmailCountsBioPageReviews() throws Exception {
        final var email = sentDecisionsEmail(new DecisionsDigest(1, 0, List.of(), List.of(), List.of(), List.of(), List.of(), 1, List.of()));

        assertThat(email.getSubject()).isEqualTo("2 decisions waiting on hrva.cc");
        assertThat(textOf(email.getText())).contains("1 link is waiting in the review queue.", "1 bio page is waiting in the review queue.");
        assertThat(linksIn(email.getText())).containsExactly("https://hrva.cc/admin/review", "https://hrva.cc/admin/review#bio-pages", SITE_LINK, HRVALABS_LINK);
    }

    @Test
    void bioPageReviewsAloneAreTheDecisionsAndTheQueueIsNotCalledEmpty() throws Exception {
        final var email = sentDecisionsEmail(new DecisionsDigest(0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), 3, List.of()));

        assertThat(email.getSubject()).isEqualTo("3 decisions waiting on hrva.cc");
        assertThat(textOf(email.getText()))
                .contains("3 bio pages are waiting in the review queue.")
                .doesNotContain("The review queue is empty")
                .doesNotContain("links are waiting")
                .doesNotContain("link is waiting");
        assertThat(linksIn(email.getText())).containsExactly("https://hrva.cc/admin/review#bio-pages", SITE_LINK, HRVALABS_LINK);
    }

    @Test
    void withoutBioPageReviewsTheEmailNamesNoBioPages() throws Exception {
        final var body = textOf(sentDecisionsEmail(everything("")).getText());

        assertThat(body).doesNotContain("bio page");
    }

    @Test
    void withNothingWaitingAnywhereTheQueueIsCalledEmpty() throws Exception {
        assertThat(textOf(sentDecisionsEmail(decisions(0)).getText())).contains("The review queue is empty");
    }

    @Test
    void visitorReportsAreMentionedOnlyWhenThereAreAny() throws Exception {
        final var body = sentDecisionsEmail(decisions(2)).getText();

        assertThat(body).doesNotContain("reported by visitors");
    }

    @Test
    void theWeeklyDigestGoesToTheAdminWithPlainTextAndHtml() throws Exception {
        final var email = sentWeeklyDigest(weeklyDigest("example.com"));

        assertThat(email.getSender()).isEqualTo("noreply@hrva.cc");
        assertThat(email.getReceivers()).containsExactly("support@hrva.cc");
        assertThat(email.getBcc()).isNull();
        assertThat(email.getSubject()).isEqualTo("hrva.cc weekly summary: 19–25 October 2026");
        assertThat(email.getPlainText()).isEqualTo("""
                hrva.cc weekly summary: 19–25 October 2026

                Clicks: 1,204 (up 12% on the week before)
                Visitors: 950 (each link's daily visitors, added up)
                Bots and link previews: 20.0% of all clicks
                New links: 40 (down 5% on the week before)
                How they were made: 30 website, 6 API key, 4 AI connector
                New users: 3 (none the week before)
                Active users: 12 signed in during the last 7 days
                Reviews: 5 opened, 4 resolved
                Review queue: 2 waiting https://hrva.cc/admin/review
                Automatic blocks: 2 blocklist, 1 Safe Browsing
                Top links by clicks:
                1. hrva.cc/my_menu → example.com, 120 clicks

                Stats (last 7 days): https://hrva.cc/admin/dashboard?days=7""");
        assertThat(textOf(email.getText())).contains("hrva.cc weekly summary: 19–25 October 2026 "
                + "Clicks: 1,204 (up 12% on the week before) "
                + "Visitors: 950 (each link's daily visitors, added up) "
                + "Bots and link previews: 20.0% of all clicks "
                + "New links: 40 (down 5% on the week before) "
                + "How they were made: 30 website, 6 API key, 4 AI connector "
                + "New users: 3 (none the week before) "
                + "Active users: 12 signed in during the last 7 days "
                + "Reviews: 5 opened, 4 resolved "
                + "Review queue: 2 waiting https://hrva.cc/admin/review "
                + "Automatic blocks: 2 blocklist, 1 Safe Browsing "
                + "Top links by clicks: "
                + "1. hrva.cc/my_menu → example.com, 120 clicks "
                + "Open stats (last 7 days) ");
        assertThat(email.getText()).contains("<title>hrva.cc weekly summary: 19–25 October 2026</title>");
    }

    @Test
    void theWeeklyDigestLinksTheReviewQueueAndTheStatsPage() throws Exception {
        final var body = sentWeeklyDigest(weeklyDigest("example.com")).getText();

        assertThat(linksIn(body)).containsExactly("https://hrva.cc/admin/review", "https://hrva.cc/admin/dashboard?days=7", SITE_LINK, HRVALABS_LINK);
    }

    @Test
    void aHostInTheWeeklyDigestIsEscapedInTheHtml() throws Exception {
        final var email = sentWeeklyDigest(weeklyDigest("<b>x</b>.example"));

        assertThat(email.getText()).doesNotContain("<b>x</b>").contains("hrva.cc/my_menu → &lt;b&gt;x&lt;/b&gt;.example, 120 clicks");
        assertThat(email.getPlainText()).contains("1. hrva.cc/my_menu → <b>x</b>.example, 120 clicks");
    }

    @Test
    void theWeeklyDigestIsLoggedWithItsHtmlSoAResendSendsTheHtml() throws Exception {
        final var email = sentWeeklyDigest(weeklyDigest("example.com"));

        assertThat(bodiesWhileSending).containsExactly(email.getText());
        assertThat(lastSavedEntry().getStatus()).isEqualTo(EmailLog.STATUS_SENT);
        assertThat(lastSavedEntry().getSubject()).isEqualTo("hrva.cc weekly summary: 19–25 October 2026");
    }

    @Test
    void aBlockedLinkEmailGoesToTheOwnerWithTheShortLinkAndNothingAboutTheBlock() throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));
        final var context = ArgumentCaptor.forClass(IContext.class);
        when(templateEngine.process(eq("url_blocked"), context.capture())).thenReturn("<p>blocked</p>");

        configuredEmailService(templateEngine).sendEmailUrlBlocked(OWNER, BLOCKED_LINK);

        assertThat(sent.getValue().getReceivers()).containsExactly("alice@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("Your link was blocked as unsafe");
        assertThat(sent.getValue().getText()).isEqualTo("<p>blocked</p>");
        assertThat(context.getValue().getVariable("short_link")).isEqualTo("https://hrva.cc/promo");
        assertThat(context.getValue().getVariableNames()).allSatisfy(name -> assertThat(String.valueOf(context.getValue().getVariable(name)))
                .doesNotContain("HaGeZi")
                .doesNotContain("jofogas.cards"));
    }

    @Test
    void blockedLinkEmailShowsTheDomainLink() throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));
        final var onDomain = Url.builder().shortUrl("promo").longUrl("https://jofogas.cards/login").domain(GO_EXAMPLE)
                .blockReason(BlockReason.BLOCKLIST).active(false).build();
        final var alsoOnDomain = Url.builder().id(2L).shortUrl("deal").longUrl("https://jofogas.cards/deal").domain(GO_EXAMPLE)
                .blockReason(BlockReason.DOMAIN_RULE).active(false).build();
        final var service = configuredEmailService(realTemplateEngine());

        service.sendEmailUrlBlocked(OWNER, onDomain);
        service.sendEmailUrlsBlocked(OWNER, List.of(alsoOnDomain, domainBlockedLinks(1).getFirst()));

        assertThat(sent.getAllValues().getFirst().getText()).contains("https://go.example.com/promo").doesNotContain("hrva.cc/promo");
        assertThat(sent.getAllValues().get(1).getText()).contains("https://go.example.com/deal<", "https://hrva.cc/deal1<")
                .doesNotContain("hrva.cc/deal<");
    }

    @Test
    void theBlockedLinkEmailSaysWhatHappenedWithoutNamingAListOrARule() throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        configuredEmailService(realTemplateEngine()).sendEmailUrlBlocked(OWNER, BLOCKED_LINK);

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
    void aDomainBlockEmailListsTheFirst20ShortLinksAndHowManyMoreWithoutNamingTheRule() throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        configuredEmailService(realTemplateEngine()).sendEmailUrlsBlocked(OWNER, domainBlockedLinks(25));

        final var email = sent.getValue();
        assertThat(email.getReceivers()).containsExactly("alice@example.com");
        assertThat(email.getSubject()).isEqualTo("Your links were blocked as unsafe");
        assertThat(email.getText())
                .contains("hrva.cc disabled 25 of your links because their destinations were reported for phishing, malware or scams.")
                .contains("https://hrva.cc/deal1<", "https://hrva.cc/deal20<")
                .doesNotContain("https://hrva.cc/deal21")
                .contains("and 5 more")
                .contains("alice@example.com", "support@hrva.cc")
                .doesNotContain("jofogas.cards")
                .doesNotContain("DOMAIN_RULE");
    }

    @Test
    void aDomainBlockEmailWithAtMost20LinksListsThemAllWithoutAMoreLine() throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        configuredEmailService(realTemplateEngine()).sendEmailUrlsBlocked(OWNER, domainBlockedLinks(20));

        assertThat(sent.getValue().getText())
                .contains("hrva.cc disabled 20 of your links")
                .contains("https://hrva.cc/deal1<", "https://hrva.cc/deal20<")
                .doesNotContain("more");
    }

    @Test
    void aDomainBlockEmailGivesTheTemplateTheFirst20ShortLinksAndTheRestAsACount() throws Exception {
        saveReturnsArgument();
        final var context = ArgumentCaptor.forClass(IContext.class);
        when(templateEngine.process(eq("urls_blocked"), context.capture())).thenReturn("<p>blocked</p>");

        configuredEmailService(templateEngine).sendEmailUrlsBlocked(OWNER, domainBlockedLinks(89));

        assertThat(context.getValue().getVariable("link_count")).isEqualTo(89);
        assertThat(context.getValue().getVariable("more_links")).isEqualTo(69);
        assertThat((List<?>) context.getValue().getVariable("short_links")).hasSize(20).first().isEqualTo("https://hrva.cc/deal1");
    }

    @Test
    void passkeyAddedEmailNamesTheLabelTheTimeAndHowToRemoveIt() throws Exception {
        final var email = sentPasskeyAddedEmail("Work YubiKey");

        assertThat(email.getReceivers()).containsExactly("alice@example.com");
        assertThat(email.getSubject()).isEqualTo("A passkey was added to your account");
        assertThat(email.getText())
                .contains("Work YubiKey", "25 October 2026 at 14:05 UTC", "/dashboard/settings", "Passkeys and security keys")
                .containsPattern("<h1[^>]*>A passkey was added</h1>");
        assertThat(textOf(email.getText()))
                .contains("Hello alice@example.com,")
                .contains("A passkey or security key named Work YubiKey was added to your hrva.cc account on 25 October 2026 at 14:05 UTC.")
                .contains("If this was you, there's nothing else to do.")
                .contains("If it wasn't you, sign in, open Settings \u2192 Passkeys and security keys (https://hrva.cc/dashboard/settings),"
                        + " remove it and change your password. If you can't sign in, email support@hrva.cc.")
                .contains("How passkeys work: https://hrva.cc/help/passkeys");
        assertThat(linksIn(email.getText()))
                .containsExactly("https://hrva.cc/dashboard/settings", "mailto:support@hrva.cc", "https://hrva.cc/help/passkeys", SITE_LINK, HRVALABS_LINK);
    }

    @Test
    void aGoogleOrGitHubAccountIsToldToSecureThatAccountInsteadOfChangingAPassword() throws Exception {
        for (final var provider : List.of("google", "github")) {
            final var user = User.builder().email("alice@example.com").apiKeySlots(4L).authProvider(provider).build();

            final var text = textOf(sentPasskeyAddedEmail(user, "Work YubiKey").getText());

            assertThat(text)
                    .as(provider)
                    .contains("If it wasn't you, sign in, open Settings \u2192 Passkeys and security keys (https://hrva.cc/dashboard/settings),"
                            + " remove it and secure your Google or GitHub account. If you can't sign in, email support@hrva.cc.")
                    .doesNotContain("change your password");
        }
    }

    @Test
    void anEmailAndPasswordAccountIsStillToldToChangeItsPassword() throws Exception {
        final var user = User.builder().email("alice@example.com").apiKeySlots(4L).authProvider("local").build();

        assertThat(textOf(sentPasskeyAddedEmail(user, "Work YubiKey").getText()))
                .contains(" remove it and change your password. If you can't sign in, email support@hrva.cc.")
                .doesNotContain("Google or GitHub");
    }

    @Test
    void aUserWithoutApiKeySlotsStillGetsThePasskeyEmail() throws Exception {
        final var user = User.builder().email("alice@example.com").apiKeySlots(null).authProvider("local").build();

        final var email = sentPasskeyAddedEmail(user, "Work YubiKey");

        assertThat(email.getReceivers()).containsExactly("alice@example.com");
        assertThat(textOf(email.getText())).contains("A passkey or security key named Work YubiKey was added");
        verify(emailService).sendEmail(any(Email.class), eq(null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accountEmails")
    void anAccountEmailRendersItsHeadlineAndExactlyItsLinks(final String template, final Consumer<DefaultSendingEmailService> send,
                                                           final String headline, final List<String> links) throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        send.accept(configuredEmailService(realTemplateEngine()));

        final var html = sent.getValue().getText();
        assertThat(textOf(html)).contains(headline);
        assertThat(html).containsPattern("<h1[^>]*>" + Pattern.quote(headline) + "</h1>");
        assertThat(linksIn(html)).containsExactlyElementsOf(Stream.concat(links.stream(), Stream.of(SITE_LINK, HRVALABS_LINK)).toList());
    }

    private static Stream<Arguments> accountEmails() {
        final var resetToken = ResetToken.builder().token("reset-token-41").createDate(LocalDateTime.of(2026, 10, 29, 9, 14, 2)).build();
        final var verificationToken = VerificationToken.builder().token("verify-token-42").build();
        final var flaggedLink = Url.builder().shortUrl("menu").longUrl("https://example.com/menu").build();
        return Stream.of(
                Arguments.of("reset_password", (Consumer<DefaultSendingEmailService>) service -> service.sendEmailForgotPassword(OWNER, resetToken),
                        "Reset your password", List.of("https://hrva.cc/reset-password/reset-token-41", "https://hrva.cc/reset-password/reset-token-41")),
                Arguments.of("verify_email", (Consumer<DefaultSendingEmailService>) service -> service.sendVerificationEmail(OWNER, verificationToken),
                        "Verify your email", List.of("https://hrva.cc/verify-email/verify-token-42", "https://hrva.cc/verify-email/verify-token-42")),
                Arguments.of("account_deactivated", (Consumer<DefaultSendingEmailService>) service -> service.sendEmailAccountDeactivated(OWNER),
                        "Account deactivated", List.of("mailto:support@hrva.cc")),
                Arguments.of("url_malware_detected", (Consumer<DefaultSendingEmailService>) service -> service.sendEmailUrlMalwareDetected(OWNER, flaggedLink, "MALWARE"),
                        "Malicious URL detected", List.of("https://hrva.cc/login/")));
    }

    @Test
    void aPasskeyLabelWithMarkupIsEscaped() throws Exception {
        final var body = sentPasskeyAddedEmail("<b>x</b><script>alert(1)</script>").getText();

        assertThat(body)
                .doesNotContain("<b>x</b>")
                .doesNotContain("<script>")
                .contains("&lt;b&gt;x&lt;/b&gt;&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void thePasskeyAddedEmailHandsTheTemplateItsVariables() throws Exception {
        saveReturnsArgument();
        final var context = ArgumentCaptor.forClass(IContext.class);
        when(templateEngine.process(eq("passkey_added"), context.capture())).thenReturn("<p>added</p>");

        configuredEmailService(templateEngine).sendPasskeyAddedEmail(OWNER, "Work YubiKey", PASSKEY_ADDED_AT);

        assertThat(context.getValue().getVariable("name")).isEqualTo("alice@example.com");
        assertThat(context.getValue().getVariable("label")).isEqualTo("Work YubiKey");
        assertThat(context.getValue().getVariable("added_at")).isEqualTo("25 October 2026 at 14:05 UTC");
        assertThat(context.getValue().getVariable("settings_url")).isEqualTo("https://hrva.cc/dashboard/settings");
        assertThat(context.getValue().getVariable("help_url")).isEqualTo("https://hrva.cc/help/passkeys");
        assertThat(context.getValue().getVariable("contact_email")).isEqualTo("support@hrva.cc");
        assertThat(context.getValue().getVariable("local_account")).isEqualTo(true);
        verify(emailService).sendEmail(any(Email.class), eq(null));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theSendLogCountsTheRecipientsAndMasksTheirAddresses(final CapturedOutput output) throws Exception {
        sentPasskeyAddedEmail("Work YubiKey");

        assertThat(output)
                .contains("Sending A passkey was added to your account email to 1 recipient(s): [a***@example.com]")
                .doesNotContain("alice@example.com");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theNewAccountNoticeIsLoggedWithTheNewAddressMaskedInItsSubject(final CapturedOutput output) {
        saveReturnsArgument();

        sendingEmailService.sendNewUserNotificationToAdmin(User.builder().email("new@example.com").build());

        assertThat(output)
                .contains("Sending New user registered: n***@example.com email to 1 recipient(s): [s***@hrva.cc]")
                .doesNotContain("new@example.com")
                .doesNotContain("support@hrva.cc");
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

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void consentedEmailIsNotSentAfterRevoke(final CapturedOutput output) throws Exception {
        when(emailConsentReader.hasActiveEmailConsent(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).thenReturn(false);

        final var result = sendingEmailService.tryToSendConsentedEmail(consentedEmail(), 7L, NotificationType.WEEKLY_SUMMARY_EMAIL);

        assertThat(result).isEmpty();
        verify(emailService, never()).sendEmail(any(Email.class), any());
        verify(emailLogRepository, never()).save(any(EmailLog.class));
        assertThat(output).contains("Consented email skipped, consent withdrawn user id=7 type=WEEKLY_SUMMARY_EMAIL")
                .doesNotContain("alice@example.com");
    }

    @Test
    void consentedEmailCarriesHeadersAndIsLogged() throws Exception {
        saveReturnsArgument();
        when(emailConsentReader.hasActiveEmailConsent(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).thenReturn(true);
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));
        final var email = consentedEmail();
        email.setHeaders(Map.of("X-Entity-Ref-ID", "weekly-2026-10-19"));

        final var result = sendingEmailService.tryToSendConsentedEmail(email, 7L, NotificationType.WEEKLY_SUMMARY_EMAIL);

        final var token = unsubscribeTokens.issue(7L, NotificationType.WEEKLY_SUMMARY_EMAIL);
        assertThat(sent.getValue().getHeaders())
                .containsEntry("List-Unsubscribe", "<https://hrva.cc/api/v1/notifications/unsubscribe?token=" + token + ">")
                .containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
                .containsEntry("X-Entity-Ref-ID", "weekly-2026-10-19");
        assertThat(result).hasValueSatisfying(entry -> {
            assertThat(entry.getConsentType()).isEqualTo(NotificationType.WEEKLY_SUMMARY_EMAIL);
            assertThat(entry.getUserId()).isEqualTo(7L);
            assertThat(entry.getStatus()).isEqualTo(EmailLog.STATUS_SENT);
            assertThat(entry.getRecipient()).isEqualTo("alice@example.com");
        });
    }

    @Test
    void channelEventsCannotBeSentAsConsentedEmail() {
        assertThatThrownBy(() -> sendingEmailService.tryToSendConsentedEmail(consentedEmail(), 7L, NotificationType.LINK_DEAD))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(emailLogRepository, emailService);
    }

    @Test
    void resendRefusesWithdrawnConsent() throws Exception {
        final var failed = failedConsentedEntry(7L);
        when(emailLogRepository.findById(21L)).thenReturn(Optional.of(failed));
        when(emailConsentReader.hasActiveEmailConsent(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).thenReturn(false);

        assertThatThrownBy(() -> sendingEmailService.resendEmail(21L))
                .isInstanceOf(EmailNotResendableException.class)
                .hasMessage("This person has turned this email off since, so it can't be resent.");
        assertThat(failed.getStatus()).isEqualTo(EmailLog.STATUS_FAILED);
        assertThat(failed.getBody()).isEqualTo("<p>last week</p>");
        verify(emailLogRepository, never()).save(any(EmailLog.class));
        verify(emailService, never()).sendEmail(any(Email.class), any());
    }

    @Test
    void resendRefusesAConsentedEmailWithoutItsUser() throws Exception {
        when(emailLogRepository.findById(21L)).thenReturn(Optional.of(failedConsentedEntry(null)));

        assertThatThrownBy(() -> sendingEmailService.resendEmail(21L))
                .isInstanceOf(EmailNotResendableException.class)
                .hasMessage("This person has turned this email off since, so it can't be resent.");
        verify(emailService, never()).sendEmail(any(Email.class), any());
    }

    @Test
    void resendOfAConsentedEmailRebuildsTheHeaders() throws Exception {
        saveReturnsArgument();
        final var failed = failedConsentedEntry(7L);
        when(emailLogRepository.findById(21L)).thenReturn(Optional.of(failed));
        when(emailConsentReader.hasActiveEmailConsent(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).thenReturn(true);
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        final var resent = sendingEmailService.resendEmail(21L);

        final var token = unsubscribeTokens.issue(7L, NotificationType.WEEKLY_SUMMARY_EMAIL);
        assertThat(sent.getValue().getHeaders()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "List-Unsubscribe", "<https://hrva.cc/api/v1/notifications/unsubscribe?token=" + token + ">",
                "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
        assertThat(sent.getValue().getText()).isEqualTo("<p>last week</p>");
        assertThat(resent.getConsentType()).isEqualTo(NotificationType.WEEKLY_SUMMARY_EMAIL);
        assertThat(resent.getUserId()).isEqualTo(7L);
        assertThat(resent.getStatus()).isEqualTo(EmailLog.STATUS_SENT);
        assertThat(failed.getStatus()).isEqualTo(EmailLog.STATUS_RESENT);
        assertThat(failed.getBody()).isNull();
    }

    @Test
    void resendOfATransactionalEmailIsUnchanged() throws Exception {
        saveReturnsArgument();
        final var failed = EmailLog.builder().id(22L).sender("noreply@hrva.cc").recipient("alice@example.com")
                .subject("Password reset token").body("<p>reset</p>").status(EmailLog.STATUS_FAILED).build();
        when(emailLogRepository.findById(22L)).thenReturn(Optional.of(failed));
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        final var resent = sendingEmailService.resendEmail(22L);

        assertThat(sent.getValue().getHeaders()).isEmpty();
        assertThat(resent.getConsentType()).isNull();
        assertThat(resent.getUserId()).isNull();
        assertThat(failed.getStatus()).isEqualTo(EmailLog.STATUS_RESENT);
        verifyNoInteractions(emailConsentReader);
    }

    @Test
    void aTransactionalEmailHasNoHeadersAndNoConsentType() throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        sendingEmailService.sendNewUserNotificationToAdmin(User.builder().email("new@example.com").build());

        assertThat(sent.getValue().getHeaders()).isEmpty();
        assertThat(lastSavedEntry().getConsentType()).isNull();
        assertThat(lastSavedEntry().getUserId()).isNull();
        verifyNoInteractions(emailConsentReader);
    }

    @Test
    void deadLinksEmailNeedsConsent() throws Exception {
        when(emailConsentReader.hasActiveEmailConsent(7L, NotificationType.DEAD_LINK_EMAIL)).thenReturn(false);

        configuredEmailService(realTemplateEngine()).sendDeadLinksEmail(deadLinksEmail("example.com", "abc"));

        verify(emailService, never()).sendEmail(any(Email.class), any());
        verify(emailLogRepository, never()).save(any(EmailLog.class));
    }

    @Test
    void deadLinksEmailListsEachLinkWithItsDayAndWhatWentWrong() throws Exception {
        final var email = sentDeadLinksEmail(deadLinksEmail("example.com", "abc", "menu"));

        final var token = unsubscribeTokens.issue(7L, NotificationType.DEAD_LINK_EMAIL);
        assertThat(email.getReceivers()).containsExactly("alice@example.com");
        assertThat(email.getSubject()).isEqualTo("2 of your links look broken");
        assertThat(textOf(email.getText())).doesNotContain("edit the link");
        assertThat(textOf(email.getText())).contains(
                "hrva.cc/abc → example.com Since 27 October 2026, the page wasn't found (404)",
                "hrva.cc/menu → example.com Since 27 October 2026, the page wasn't found (404)",
                "If a page moved, shorten its new address and turn the old link off on your links page.",
                "Open your links",
                "You get this because you turned on dead-link emails.",
                "Turn off dead-link emails",
                "Notification settings");
        assertThat(linksIn(email.getText())).containsExactly(
                "https://hrva.cc/dashboard",
                "https://hrva.cc/unsubscribe?token=" + token,
                "https://hrva.cc/dashboard/settings#notifications",
                SITE_LINK,
                HRVALABS_LINK);
        assertThat(email.getPlainText()).isEqualTo("""
                2 of your links look broken

                hrva.cc/abc → example.com, since 27 October 2026, the page wasn't found (404)
                hrva.cc/menu → example.com, since 27 October 2026, the page wasn't found (404)

                Open your links: https://hrva.cc/dashboard

                You get this because you turned on dead-link emails.
                Turn off dead-link emails: https://hrva.cc/unsubscribe?token=%s""".formatted(token));
        assertThat(email.getHeaders()).containsEntry("List-Unsubscribe", "<https://hrva.cc/api/v1/notifications/unsubscribe?token=" + token + ">")
                .containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        assertThat(lastSavedEntry().getConsentType()).isEqualTo(NotificationType.DEAD_LINK_EMAIL);
        assertThat(lastSavedEntry().getUserId()).isEqualTo(7L);
    }

    @Test
    void oneDeadLinkIsSingularInTheSubject() throws Exception {
        assertThat(sentDeadLinksEmail(deadLinksEmail("example.com", "abc")).getSubject()).isEqualTo("1 of your links looks broken");
    }

    @Test
    void deadLinksTemplateEscapesValues() throws Exception {
        final var email = sentDeadLinksEmail(deadLinksEmail("<b>x</b>.example", "abc"));

        assertThat(email.getText()).doesNotContain("<b>x</b>").contains("hrva.cc/abc → &lt;b&gt;x&lt;/b&gt;.example");
    }

    @Test
    void aDeadLinksEmailWithMoreThanTwentyLinksSaysHowManyMore() throws Exception {
        final var aliases = IntStream.rangeClosed(1, 23).mapToObj(index -> "link" + index).toArray(String[]::new);

        final var email = sentDeadLinksEmail(deadLinksEmail("example.com", aliases));

        assertThat(email.getSubject()).isEqualTo("23 of your links look broken");
        assertThat(textOf(email.getText())).contains("hrva.cc/link20 → example.com", "and 3 more").doesNotContain("hrva.cc/link21");
        assertThat(email.getPlainText()).contains("hrva.cc/link20 → example.com, since 27 October 2026, the page wasn't found (404)\nand 3 more\n")
                .doesNotContain("hrva.cc/link21");
    }

    @Test
    void aWeeklySummaryEmailSaysHowManyMoreLinksThereAre() throws Exception {
        final var deadLinks = IntStream.rangeClosed(1, 20)
                .mapToObj(index -> new DeadLink(index, 7L, "dead" + index, "example.net", LocalDateTime.of(2026, 10, 22, 1, 0), "410", null))
                .toList();
        final var endingSoon = IntStream.rangeClosed(1, 20)
                .mapToObj(index -> new WeeklySummary.EndingSoon("soon" + index, "example.org", "EXPIRES", LocalDate.of(2026, 10, 30), null, null))
                .toList();
        final var summary = new WeeklySummary(7L, "alice@example.com", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25),
                new WeeklyDigest.Change(0, 0), 0, List.of(), deadLinks, endingSoon, 4, 1);

        final var email = sentWeeklySummaryEmail(summary);

        assertThat(textOf(email.getText())).contains(
                "hrva.cc/dead20 → example.net, since 22 October 2026, the page was removed (410) and 4 more Ending soon",
                "hrva.cc/soon20 → example.org, expires on 30 October 2026 and 1 more Open your links");
        assertThat(email.getPlainText()).contains(
                "hrva.cc/dead20 → example.net, since 22 October 2026, the page was removed (410)\nand 4 more\nEnding soon:",
                "hrva.cc/soon20 → example.org, expires on 30 October 2026\nand 1 more\n\nOpen your links");
    }

    @Test
    void weeklySummaryEmailShowsTheDomainLink() throws Exception {
        final var summary = new WeeklySummary(7L, "alice@example.com", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25),
                new WeeklyDigest.Change(90, 0), 50, List.of(new WeeklyDigest.TopLink("menu", "example.com", 90, "go.example.com")),
                List.of(new DeadLink(3L, 7L, "dead", "example.net", LocalDateTime.of(2026, 10, 22, 1, 0), "410", "go.example.com")),
                List.of(new WeeklySummary.EndingSoon("soon", "example.org", "EXPIRES", LocalDate.of(2026, 10, 30), null, "go.example.com")));

        final var email = sentWeeklySummaryEmail(summary);

        assertThat(email.getPlainText()).contains(
                "1. go.example.com/menu " + ARROW + " example.com, 90 clicks",
                "go.example.com/dead " + ARROW + " example.net, since 22 October 2026",
                "go.example.com/soon " + ARROW + " example.org, expires on 30 October 2026").doesNotContain("hrva.cc/menu", "hrva.cc/dead", "hrva.cc/soon");
        assertThat(textOf(email.getText())).contains("go.example.com/menu " + ARROW + " example.com", "go.example.com/dead " + ARROW + " example.net",
                "go.example.com/soon " + ARROW + " example.org");
        assertThat(linksIn(email.getText())).noneMatch(link -> link.contains("go.example.com"));
    }

    @Test
    void deadLinksEmailShowsTheDomainLink() throws Exception {
        final var onDomain = new DeadLink(1L, 7L, "menu", "example.com", LocalDateTime.of(2026, 10, 26, 23, 30), "404", "go.example.com");

        final var email = sentDeadLinksEmail(new DeadLinksEmail(7L, "alice@example.com", List.of(onDomain), "https://hrva.cc/dashboard"));

        assertThat(textOf(email.getText())).contains("go.example.com/menu " + ARROW + " example.com Since 27 October 2026");
        assertThat(email.getPlainText()).contains("go.example.com/menu " + ARROW + " example.com, since 27 October 2026").doesNotContain("hrva.cc/menu");
    }

    @Test
    void theWeeklyDigestShowsTheDomainLink() throws Exception {
        final var digest = weeklyDigest("example.com");
        final var onDomain = new WeeklyDigest(digest.from(), digest.to(), digest.clicks(), digest.uniqueVisitors(), digest.botClicks(), digest.newLinks(),
                digest.linksByChannel(), digest.newUsers(), digest.activeUsers(), digest.reviewsOpened(), digest.reviewsResolved(), digest.openReviews(),
                digest.automaticBlocks(), List.of(new WeeklyDigest.TopLink("menu", "example.com", 120, "go.example.com")));

        final var email = sentWeeklyDigest(onDomain);

        assertThat(email.getPlainText()).contains("1. go.example.com/menu " + ARROW + " example.com, 120 clicks");
        assertThat(textOf(email.getText())).contains("1. go.example.com/menu " + ARROW + " example.com, 120 clicks");
    }

    @Test
    void weeklySummaryEmailNeedsConsentRightBeforeItLeaves() throws Exception {
        when(emailConsentReader.hasActiveEmailConsent(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).thenReturn(false);

        configuredEmailService(realTemplateEngine()).sendWeeklySummaryEmail(weeklySummary());

        verify(emailService, never()).sendEmail(any(Email.class), any());
        verify(emailLogRepository, never()).save(any(EmailLog.class));
    }

    @Test
    void weeklySummaryHasPlainTextAndHeaders() throws Exception {
        final var email = sentWeeklySummaryEmail(weeklySummary());

        final var token = unsubscribeTokens.issue(7L, NotificationType.WEEKLY_SUMMARY_EMAIL);
        final var week = WeeklyDigestText.weekLabel(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25));
        assertThat(email.getReceivers()).containsExactly("alice@example.com");
        assertThat(email.getSubject()).isEqualTo("Your hrva.cc week: " + week);
        assertThat(email.getPlainText()).isEqualTo("""
                Your hrva.cc week: %s

                Clicks: 210 (up 17%% on the week before)
                Visitors: 150 (each link's daily visitors, added up)
                Top links:
                1. hrva.cc/abc → example.com, 90 clicks
                Links that stopped working:
                hrva.cc/dead → example.net, since 22 October 2026, the page was removed (410)
                Ending soon:
                hrva.cc/soon → example.org, expires on 30 October 2026
                hrva.cc/limited → example.org, 9 visits left

                Open your links: https://hrva.cc/dashboard

                You get this because you turned on the weekly summary.
                Turn off the weekly summary: https://hrva.cc/unsubscribe?token=%s""".formatted(week, token));
        assertThat(textOf(email.getText())).contains(
                "Clicks Clicks: 210 (up 17% on the week before) Visitors: 150 (each link's daily visitors, added up)",
                "Top links 1. hrva.cc/abc → example.com, 90 clicks",
                "Links that stopped working hrva.cc/dead → example.net, since 22 October 2026, the page was removed (410)",
                "Ending soon hrva.cc/soon → example.org, expires on 30 October 2026 hrva.cc/limited → example.org, 9 visits left",
                "Open your links",
                "You get this because you turned on the weekly summary.",
                "Turn off the weekly summary",
                "Notification settings");
        assertThat(linksIn(email.getText())).containsExactly(
                "https://hrva.cc/dashboard",
                "https://hrva.cc/unsubscribe?token=" + token,
                "https://hrva.cc/dashboard/settings#notifications",
                SITE_LINK,
                HRVALABS_LINK);
        assertThat(email.getHeaders()).containsEntry("List-Unsubscribe", "<https://hrva.cc/api/v1/notifications/unsubscribe?token=" + token + ">")
                .containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        assertThat(lastSavedEntry().getConsentType()).isEqualTo(NotificationType.WEEKLY_SUMMARY_EMAIL);
        assertThat(lastSavedEntry().getUserId()).isEqualTo(7L);
        assertThat(bodiesWhileSending).containsExactly(email.getText());
    }

    @Test
    void aWeeklySummaryEmailLeavesOutEmptySections() throws Exception {
        final var summary = new WeeklySummary(7L, "alice@example.com", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25),
                new WeeklyDigest.Change(0, 4), 0, List.of(), List.of(), List.of(
                new WeeklySummary.EndingSoon("soon", "example.org", "EXPIRES", LocalDate.of(2026, 10, 30), null, null)));

        final var text = textOf(sentWeeklySummaryEmail(summary).getText());

        assertThat(text).contains("Clicks: 0 (4 the week before)", "Ending soon hrva.cc/soon")
                .doesNotContain("Top links", "Links that stopped working");
    }

    @Test
    void aWeeklySummaryEmailEscapesValues() throws Exception {
        final var summary = new WeeklySummary(7L, "alice@example.com", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25),
                new WeeklyDigest.Change(5, 0), 5, List.of(new WeeklyDigest.TopLink("abc", "<b>x</b>.example", 5, null)), List.of(), List.of());

        final var email = sentWeeklySummaryEmail(summary);

        assertThat(email.getText()).doesNotContain("<b>x</b>").contains("hrva.cc/abc → &lt;b&gt;x&lt;/b&gt;.example, 5 clicks");
    }

    @Test
    void domainApprovedCarriesTheRecord() throws Exception {
        final var email = sentDomainEmails(service -> service.sendDomainApprovedEmail("alice@example.com", "go.example.com",
                DnsRecord.cname("go.example.com", "domains.hrva.cc"))).getFirst();

        assertThat(email.getReceivers()).containsExactly("alice@example.com");
        assertThat(email.getSubject()).isEqualTo("Your domain go.example.com is approved: one step left");
        assertThat(email.getText()).containsPattern("<h1[^>]*>Domain approved: one step left</h1>");
        assertThat(textOf(email.getText())).contains(
                "Hi alice@example.com,",
                "We approved go.example.com. One step left: add this CNAME record where the domain's DNS is managed.",
                "Type: CNAME Name: go.example.com Target: domains.hrva.cc",
                "Some DNS providers want only the first part of the name (go). If your DNS is on Cloudflare, set the record to DNS only (grey cloud).",
                "Open Custom domains",
                "How to add a CNAME");
        assertThat(linksIn(email.getText())).containsExactly(
                "https://hrva.cc/dashboard/settings#custom-domains",
                "https://hrva.cc/help/custom-domains#add-the-record",
                SITE_LINK,
                HRVALABS_LINK);
        assertThat(email.getPlainText()).isEqualTo("""
                Your domain go.example.com is approved: one step left

                We approved go.example.com. One step left: add this CNAME record where the domain's DNS is managed.

                Type: CNAME
                Name: go.example.com
                Target: domains.hrva.cc

                Some DNS providers want only the first part of the name (go). If your DNS is on Cloudflare, set the record to DNS only (grey cloud).

                Open Custom domains: https://hrva.cc/dashboard/settings#custom-domains
                How to add a CNAME: https://hrva.cc/help/custom-domains#add-the-record""");
    }

    @Test
    void domainApprovedNamesTheFirstPartOfADeeperName() throws Exception {
        final var email = sentDomainEmails(service -> service.sendDomainApprovedEmail("alice@example.com", "links.shop.example.co.uk",
                DnsRecord.cname("links.shop.example.co.uk", "domains.hrva.cc"))).getFirst();

        assertThat(textOf(email.getText())).contains("Name: links.shop.example.co.uk", "Some DNS providers want only the first part of the name (links).");
        assertThat(email.getPlainText()).contains("Name: links.shop.example.co.uk\n", "the first part of the name (links).");
    }

    @Test
    void domainRejectedShowsTheNoteOnlyWhenGiven() throws Exception {
        final var emails = sentDomainEmails(service -> {
            service.sendDomainRejectedEmail("alice@example.com", "go.example.com", "Use a domain you own");
            service.sendDomainRejectedEmail("alice@example.com", "go.example.com", null);
        });

        final var withNote = emails.getFirst();
        assertThat(withNote.getReceivers()).containsExactly("alice@example.com");
        assertThat(withNote.getSubject()).isEqualTo("Your domain application for go.example.com");
        assertThat(withNote.getText()).containsPattern("<h1[^>]*>Domain not approved</h1>");
        assertThat(textOf(withNote.getText())).contains(
                "Hi alice@example.com,",
                "We didn't approve go.example.com.",
                "Note from the admin: Use a domain you own",
                "If you think that's a mistake, contact support@hrva.cc.");
        assertThat(linksIn(withNote.getText())).containsExactly("mailto:support@hrva.cc", SITE_LINK, HRVALABS_LINK);
        assertThat(withNote.getPlainText()).isEqualTo("""
                Your domain application for go.example.com

                We didn't approve go.example.com.
                Note from the admin: Use a domain you own

                If you think that's a mistake, contact support@hrva.cc.""");
        final var withoutNote = emails.get(1);
        assertThat(textOf(withoutNote.getText())).contains("We didn't approve go.example.com.").doesNotContain("Note from the admin");
        assertThat(withoutNote.getPlainText()).isEqualTo("""
                Your domain application for go.example.com

                We didn't approve go.example.com.

                If you think that's a mistake, contact support@hrva.cc.""");
    }

    @Test
    void domainDisabledSaysWhy() throws Exception {
        final var emails = sentDomainEmails(service -> {
            service.sendDomainDisabledEmail("alice@example.com", "go.example.com", "Phishing kit");
            service.sendDomainDisabledEmail("alice@example.com", "go.example.com", "Turned off because the domain is on a blocklist.");
        });

        final var byAnAdmin = emails.getFirst();
        assertThat(byAnAdmin.getReceivers()).containsExactly("alice@example.com");
        assertThat(byAnAdmin.getSubject()).isEqualTo("Your domain go.example.com was turned off");
        assertThat(byAnAdmin.getText()).containsPattern("<h1[^>]*>Domain turned off</h1>");
        assertThat(textOf(byAnAdmin.getText())).contains(
                "Hi alice@example.com,",
                "We turned off go.example.com. Its links stop working until it's turned back on.",
                "Reason Phishing kit",
                "If you think that's a mistake, contact support@hrva.cc.");
        assertThat(linksIn(byAnAdmin.getText())).containsExactly("mailto:support@hrva.cc", SITE_LINK, HRVALABS_LINK);
        assertThat(byAnAdmin.getPlainText()).isEqualTo("""
                Your domain go.example.com was turned off

                We turned off go.example.com. Its links stop working until it's turned back on.
                Reason: Phishing kit

                If you think that's a mistake, contact support@hrva.cc.""");
        assertThat(textOf(emails.get(1).getText())).contains("Reason Turned off because the domain is on a blocklist.");
        assertThat(emails.get(1).getPlainText()).contains("\nReason: Turned off because the domain is on a blocklist.\n");
    }

    @Test
    void domainRemovedSummarizesTheLinks() throws Exception {
        final var renames = IntStream.rangeClosed(1, 25).mapToObj(number -> new DomainRename("menu" + number, "new" + number)).toList();
        final var email = sentDomainEmails(service -> service.sendDomainRemovedEmail("alice@example.com", "go.example.com",
                new DomainRemovalResponse(30, renames, 0), DomainLinkMode.MOVE)).getFirst();

        assertThat(email.getReceivers()).containsExactly("alice@example.com");
        assertThat(email.getSubject()).isEqualTo("Your domain go.example.com was removed");
        assertThat(email.getText()).containsPattern("<h1[^>]*>Domain removed</h1>")
                .contains(">go.example.com/menu1" + ARROW_LINE + "hrva.cc/new1<", ">go.example.com/menu20" + ARROW_LINE + "hrva.cc/new20<")
                .doesNotContain("menu21");
        assertThat(textOf(email.getText())).contains(
                "Hi alice@example.com,",
                "We removed go.example.com.",
                "30 links moved to hrva.cc. These got a new alias because theirs isn't free on hrva.cc:",
                "and 5 more",
                "If you think that's a mistake, contact support@hrva.cc.");
        assertThat(linksIn(email.getText())).containsExactly("mailto:support@hrva.cc", SITE_LINK, HRVALABS_LINK);
        final var renameLines = IntStream.rangeClosed(1, 20).mapToObj(number -> "go.example.com/menu" + number + ARROW_LINE + "hrva.cc/new" + number)
                .collect(Collectors.joining("\n"));
        assertThat(email.getPlainText()).isEqualTo("""
                Your domain go.example.com was removed

                We removed go.example.com.
                30 links moved to hrva.cc. These got a new alias because theirs isn't free on hrva.cc:
                %s
                and 5 more

                If you think that's a mistake, contact support@hrva.cc.""".formatted(renameLines));
    }

    @Test
    void domainRemovedSaysHowManyLinksWereTurnedOffOrMoved() throws Exception {
        final var emails = sentDomainEmails(service -> {
            service.sendDomainRemovedEmail("alice@example.com", "go.example.com", new DomainRemovalResponse(0, List.of(), 2), DomainLinkMode.DEACTIVATE);
            service.sendDomainRemovedEmail("alice@example.com", "go.example.com", new DomainRemovalResponse(0, List.of(), 1), DomainLinkMode.DEACTIVATE);
            service.sendDomainRemovedEmail("alice@example.com", "go.example.com", new DomainRemovalResponse(1, List.of(), 0), DomainLinkMode.MOVE);
            service.sendDomainRemovedEmail("alice@example.com", "go.example.com", DomainRemovalResponse.nothing(), DomainLinkMode.DEACTIVATE);
        });

        assertThat(textOf(emails.getFirst().getText())).contains("We removed go.example.com. 2 links were turned off.").doesNotContain("moved to", "alias");
        assertThat(emails.getFirst().getPlainText()).contains("\nWe removed go.example.com.\n2 links were turned off.\n");
        assertThat(textOf(emails.get(1).getText())).contains("1 link was turned off.");
        assertThat(textOf(emails.get(2).getText())).contains("We removed go.example.com. 1 link moved to hrva.cc. If you think")
                .doesNotContain("alias", "and 0 more");
        assertThat(textOf(emails.get(3).getText())).contains("We removed go.example.com. If you think").doesNotContain("links", "link was");
        assertThat(emails.get(3).getPlainText()).isEqualTo("""
                Your domain go.example.com was removed

                We removed go.example.com.

                If you think that's a mistake, contact support@hrva.cc.""");
    }

    @Test
    void domainEmailsAreTransactional() throws Exception {
        final var emails = sentDomainEmails(service -> {
            service.sendDomainApprovedEmail("alice@example.com", "go.example.com", DnsRecord.cname("go.example.com", "domains.hrva.cc"));
            service.sendDomainRejectedEmail("alice@example.com", "go.example.com", "Use a domain you own");
            service.sendDomainDisabledEmail("alice@example.com", "go.example.com", "Phishing kit");
            service.sendDomainRemovedEmail("alice@example.com", "go.example.com", new DomainRemovalResponse(0, List.of(), 2), DomainLinkMode.DEACTIVATE);
        });

        assertThat(emails).hasSize(4).allSatisfy(email -> {
            assertThat(email.getHeaders()).isEmpty();
            assertThat(email.getText()).doesNotContain("unsubscribe", "Notification settings", "You get this because");
            assertThat(email.getPlainText()).doesNotContain("unsubscribe", "You get this because");
        });
        final var logged = ArgumentCaptor.forClass(EmailLog.class);
        verify(emailLogRepository, atLeastOnce()).save(logged.capture());
        assertThat(logged.getAllValues()).extracting(EmailLog::getSubject).containsSubsequence(
                "Your domain go.example.com is approved: one step left",
                "Your domain application for go.example.com",
                "Your domain go.example.com was turned off",
                "Your domain go.example.com was removed");
        assertThat(logged.getAllValues()).allSatisfy(entry -> {
            assertThat(entry.getConsentType()).isNull();
            assertThat(entry.getUserId()).isNull();
        });
        verifyNoInteractions(emailConsentReader);
    }

    @Test
    void domainEmailsEscapeWhatAdminsWrite() throws Exception {
        final var emails = sentDomainEmails(service -> {
            service.sendDomainRejectedEmail("alice@example.com", "go.example.com", "<b>no</b><script>alert(1)</script>");
            service.sendDomainDisabledEmail("alice@example.com", "go.example.com", "<i>spam</i>");
        });

        assertThat(emails.getFirst().getText()).doesNotContain("<b>no</b>", "<script>").contains("&lt;b&gt;no&lt;/b&gt;&lt;script&gt;");
        assertThat(emails.getFirst().getPlainText()).contains("Note from the admin: <b>no</b><script>alert(1)</script>");
        assertThat(emails.get(1).getPlainText()).contains("Reason: <i>spam</i>");
        assertThat(emails.get(1).getText()).doesNotContain("<i>spam</i>").contains("&lt;i&gt;spam&lt;/i&gt;");
    }

    private List<Email> sentDomainEmails(final Consumer<DefaultSendingEmailService> send) throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        send.accept(configuredEmailService(realTemplateEngine()));

        return sent.getAllValues();
    }

    private Email sentWeeklySummaryEmail(final WeeklySummary summary) throws Exception {
        saveReturnsArgument();
        when(emailConsentReader.hasActiveEmailConsent(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).thenReturn(true);
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        configuredEmailService(realTemplateEngine()).sendWeeklySummaryEmail(summary);

        return sent.getValue();
    }

    private static WeeklySummary weeklySummary() {
        return new WeeklySummary(7L, "alice@example.com", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), new WeeklyDigest.Change(210, 180), 150,
                List.of(new WeeklyDigest.TopLink("abc", "example.com", 90, null)),
                List.of(new DeadLink(3L, 7L, "dead", "example.net", LocalDateTime.of(2026, 10, 22, 1, 0), "410", null)),
                List.of(new WeeklySummary.EndingSoon("soon", "example.org", "EXPIRES", LocalDate.of(2026, 10, 30), null, null),
                        new WeeklySummary.EndingSoon("limited", "example.org", "VISIT_LIMIT", null, 9L, null)));
    }

    private Email sentDeadLinksEmail(final DeadLinksEmail deadLinks) throws Exception {
        saveReturnsArgument();
        when(emailConsentReader.hasActiveEmailConsent(7L, NotificationType.DEAD_LINK_EMAIL)).thenReturn(true);
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        configuredEmailService(realTemplateEngine()).sendDeadLinksEmail(deadLinks);

        return sent.getValue();
    }

    private static DeadLinksEmail deadLinksEmail(final String host, final String... aliases) {
        final var links = IntStream.range(0, aliases.length)
                .mapToObj(index -> new DeadLink(index + 1L, 7L, aliases[index], host, LocalDateTime.of(2026, 10, 26, 23, 30), "404", null))
                .toList();
        return new DeadLinksEmail(7L, "alice@example.com", links, "https://hrva.cc/dashboard");
    }

    private static Email consentedEmail() {
        return Email.builder()
                .sender("noreply@hrva.cc")
                .receivers(new String[]{"alice@example.com"})
                .subject("Your week on hrva.cc")
                .text("<p>last week</p>")
                .build();
    }

    private static EmailLog failedConsentedEntry(final Long userId) {
        return EmailLog.builder().id(21L).sender("noreply@hrva.cc").recipient("alice@example.com").subject("Your week on hrva.cc")
                .body("<p>last week</p>").status(EmailLog.STATUS_FAILED).consentType(NotificationType.WEEKLY_SUMMARY_EMAIL).userId(userId).build();
    }

    private static final String SENDING_IS_OFF = "Not sent: Send emails is off";

    private static Stream<Arguments> nonAccountEmails() {
        final var domainRemoval = new DomainRemovalResponse(1, List.of(), 0);
        final var dead = NotificationType.DEAD_LINK_EMAIL;
        final var weekly = NotificationType.WEEKLY_SUMMARY_EMAIL;
        final var expiring = NotificationType.EXPIRY_REMINDER_EMAIL;
        return Stream.of(
                nonAccountEmail("sendEmailUrlBlocked", EmailKind.LINK_BLOCKED, null, "alice@example.com",
                        service -> service.sendEmailUrlBlocked(OWNER, BLOCKED_LINK)),
                nonAccountEmail("sendEmailUrlsBlocked", EmailKind.LINK_BLOCKED, null, "alice@example.com",
                        service -> service.sendEmailUrlsBlocked(OWNER, domainBlockedLinks(2))),
                nonAccountEmail("sendEmailUrlMalwareDetected", EmailKind.MALWARE_FOUND, null, "alice@example.com",
                        service -> service.sendEmailUrlMalwareDetected(OWNER, BLOCKED_LINK, "MALWARE")),
                nonAccountEmail("sendDecisionsEmailToAdmin", EmailKind.ADMIN_DAILY, null, "support@hrva.cc",
                        service -> service.sendDecisionsEmailToAdmin(decisions(0))),
                nonAccountEmail("sendNewUserNotificationToAdmin", EmailKind.NEW_USER_NOTICE, null, "support@hrva.cc",
                        service -> service.sendNewUserNotificationToAdmin(User.builder().email("new@example.com").build())),
                nonAccountEmail("sendWeeklyDigestToAdmin", EmailKind.ADMIN_WEEKLY_DIGEST, null, "support@hrva.cc",
                        service -> service.sendWeeklyDigestToAdmin(weeklyDigest("example.com"))),
                nonAccountEmail("sendDeadLinksEmail", EmailKind.DEAD_LINKS, dead, "alice@example.com",
                        service -> service.sendDeadLinksEmail(deadLinksEmail("example.com", "menu"))),
                nonAccountEmail("sendWeeklySummaryEmail", EmailKind.WEEKLY_SUMMARY, weekly, "alice@example.com",
                        service -> service.sendWeeklySummaryEmail(weeklySummary())),
                nonAccountEmail("the expiry reminder", EmailKind.LINK_EXPIRING, expiring, "alice@example.com",
                        service -> service.tryToSendConsentedEmail(consentedEmail(), 7L, expiring)),
                nonAccountEmail("sendDomainApprovedEmail", EmailKind.CUSTOM_DOMAIN, null, "alice@example.com",
                        service -> service.sendDomainApprovedEmail("alice@example.com", "go.example.com", DnsRecord.cname("go.example.com", "domains.hrva.cc"))),
                nonAccountEmail("sendDomainRejectedEmail", EmailKind.CUSTOM_DOMAIN, null, "alice@example.com",
                        service -> service.sendDomainRejectedEmail("alice@example.com", "go.example.com", "Use a domain you own")),
                nonAccountEmail("sendDomainDisabledEmail", EmailKind.CUSTOM_DOMAIN, null, "alice@example.com",
                        service -> service.sendDomainDisabledEmail("alice@example.com", "go.example.com", "Phishing kit")),
                nonAccountEmail("sendDomainRemovedEmail", EmailKind.CUSTOM_DOMAIN, null, "alice@example.com",
                        service -> service.sendDomainRemovedEmail("alice@example.com", "go.example.com", domainRemoval, DomainLinkMode.MOVE)));
    }

    private static Arguments nonAccountEmail(final String name, final EmailKind kind, final NotificationType consentType, final String recipient,
                                             final Consumer<DefaultSendingEmailService> send) {
        return Arguments.of(name, kind, consentType, recipient, send);
    }

    private static Stream<Arguments> accountEmailSenders() {
        final var resetToken = ResetToken.builder().token("reset-token-41").createDate(LocalDateTime.of(2026, 10, 29, 9, 14, 2)).build();
        final var verificationToken = VerificationToken.builder().token("verify-token-42").build();
        return Stream.of(
                Arguments.of("reset_password", (Consumer<DefaultSendingEmailService>) service -> service.sendEmailForgotPassword(OWNER, resetToken)),
                Arguments.of("verify_email", (Consumer<DefaultSendingEmailService>) service -> service.sendVerificationEmail(OWNER, verificationToken)),
                Arguments.of("passkey_added", (Consumer<DefaultSendingEmailService>) service -> service.sendPasskeyAddedEmail(OWNER, "Laptop", PASSKEY_ADDED_AT)),
                Arguments.of("account_deactivated", (Consumer<DefaultSendingEmailService>) service -> service.sendEmailAccountDeactivated(OWNER)));
    }

    private List<EmailLog> savedEntries() {
        final var saved = ArgumentCaptor.forClass(EmailLog.class);
        verify(emailLogRepository, atLeastOnce()).save(saved.capture());
        return saved.getAllValues();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonAccountEmails")
    void sendEmailsOffLogsEveryNonAccountEmailAsNotSent(final String name, final EmailKind kind, final NotificationType consentType,
                                                        final String recipient, final Consumer<DefaultSendingEmailService> send) {
        saveReturnsArgument();
        when(settingsService.emailSwitchOff(any(EmailKind.class))).thenReturn(Optional.of(SettingKey.SEND_EMAILS));
        if (consentType != null) {
            when(emailConsentReader.hasActiveEmailConsent(7L, consentType)).thenReturn(true);
        }

        send.accept(configuredEmailService(realTemplateEngine()));

        verify(settingsService).emailSwitchOff(kind);
        verifyNoInteractions(emailService);
        assertThat(savedEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.getStatus()).isEqualTo(EmailLog.STATUS_SKIPPED).isEqualTo("SKIPPED");
            assertThat(entry.getErrorMessage()).isEqualTo(SENDING_IS_OFF);
            assertThat(entry.getBody()).isNull();
            assertThat(entry.getSender()).isEqualTo("noreply@hrva.cc");
            assertThat(entry.getRecipient()).isEqualTo(recipient);
            assertThat(entry.getSubject()).isNotBlank();
            assertThat(entry.getSentAt()).isNull();
            assertThat(entry.isResendable()).isFalse();
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accountEmailSenders")
    void accountEmailsGoOutWithEverySwitchOff(final String template, final Consumer<DefaultSendingEmailService> send) throws Exception {
        saveReturnsArgument();
        lenient().when(settingsService.emailSwitchOff(any(EmailKind.class))).thenReturn(Optional.of(SettingKey.SEND_EMAILS));

        send.accept(configuredEmailService(realTemplateEngine()));

        verify(emailService).sendEmail(any(Email.class), eq(null));
        verifyNoInteractions(settingsService);
        assertThat(savedEntries()).extracting(EmailLog::getStatus).doesNotContain(EmailLog.STATUS_SKIPPED).contains(EmailLog.STATUS_SENT);
    }

    @Test
    void aKindSwitchOffStopsOnlyThatKind() throws Exception {
        saveReturnsArgument();
        when(settingsService.emailSwitchOff(EmailKind.LINK_BLOCKED)).thenReturn(Optional.of(SettingKey.EMAIL_LINK_BLOCKED));
        when(settingsService.emailSwitchOff(EmailKind.MALWARE_FOUND)).thenReturn(Optional.empty());
        final var service = configuredEmailService(realTemplateEngine());

        service.sendEmailUrlBlocked(OWNER, BLOCKED_LINK);
        service.sendEmailUrlMalwareDetected(OWNER, BLOCKED_LINK, "MALWARE");

        verify(emailService, times(1)).sendEmail(any(Email.class), eq(null));
        assertThat(savedEntries()).extracting(EmailLog::getStatus, EmailLog::getErrorMessage)
                .contains(Tuple.tuple(EmailLog.STATUS_SKIPPED, "Not sent: Link blocked is off"), Tuple.tuple(EmailLog.STATUS_SENT, null));
    }

    @Test
    void withoutConsentNothingIsLogged() {
        when(emailConsentReader.hasActiveEmailConsent(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).thenReturn(false);

        final var result = sendingEmailService.tryToSendConsentedEmail(consentedEmail(), 7L, NotificationType.WEEKLY_SUMMARY_EMAIL);

        assertThat(result).isEmpty();
        verifyNoInteractions(settingsService, emailLogRepository, emailService);
    }

    @Test
    void withConsentAndTheSwitchOffTheEmailIsLoggedNotSent() {
        saveReturnsArgument();
        when(emailConsentReader.hasActiveEmailConsent(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).thenReturn(true);
        when(settingsService.emailSwitchOff(EmailKind.WEEKLY_SUMMARY)).thenReturn(Optional.of(SettingKey.EMAIL_WEEKLY_SUMMARY));

        final var result = sendingEmailService.tryToSendConsentedEmail(consentedEmail(), 7L, NotificationType.WEEKLY_SUMMARY_EMAIL);

        assertThat(result).isEmpty();
        verifyNoInteractions(emailService);
        assertThat(savedEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.getStatus()).isEqualTo(EmailLog.STATUS_SKIPPED);
            assertThat(entry.getErrorMessage()).isEqualTo("Not sent: Weekly link summary is off");
            assertThat(entry.getBody()).isNull();
            assertThat(entry.getConsentType()).isEqualTo(NotificationType.WEEKLY_SUMMARY_EMAIL);
            assertThat(entry.getUserId()).isEqualTo(7L);
            assertThat(entry.getRecipient()).isEqualTo("alice@example.com");
            assertThat(entry.getSubject()).isEqualTo("Your week on hrva.cc");
        });
    }

    @ParameterizedTest
    @CsvSource({
            "DEAD_LINK_EMAIL, DEAD_LINKS",
            "WEEKLY_SUMMARY_EMAIL, WEEKLY_SUMMARY",
            "EXPIRY_REMINDER_EMAIL, LINK_EXPIRING"})
    void aConsentedEmailAsksForTheKindOfItsNotificationType(final NotificationType type, final EmailKind kind) {
        saveReturnsArgument();
        when(emailConsentReader.hasActiveEmailConsent(7L, type)).thenReturn(true);
        when(settingsService.emailSwitchOff(kind)).thenReturn(Optional.of(SettingKey.SEND_EMAILS));

        sendingEmailService.tryToSendConsentedEmail(consentedEmail(), 7L, type);

        verify(settingsService).emailSwitchOff(kind);
        assertThat(savedEntries()).singleElement().extracting(EmailLog::getStatus).isEqualTo(EmailLog.STATUS_SKIPPED);
    }

    @Test
    void aSkippedEmailCannotBeResent() {
        final var skipped = EmailLog.builder().id(31L).sender("noreply@hrva.cc").recipient("alice@example.com").subject("Your link was blocked as unsafe")
                .status(EmailLog.STATUS_SKIPPED).errorMessage(SENDING_IS_OFF).body("<p>kept by mistake</p>").build();
        when(emailLogRepository.findById(31L)).thenReturn(Optional.of(skipped));

        assertThat(skipped.isResendable()).isFalse();
        assertThatThrownBy(() -> sendingEmailService.resendEmail(31L)).isInstanceOf(EmailNotResendableException.class);
        verifyNoInteractions(emailService, settingsService);
    }

    @Test
    void resendIsNotGated() throws Exception {
        saveReturnsArgument();
        lenient().when(settingsService.emailSwitchOff(any(EmailKind.class))).thenReturn(Optional.of(SettingKey.SEND_EMAILS));
        final var failed = EmailLog.builder().id(32L).sender("noreply@hrva.cc").recipient("alice@example.com").subject("Your link was blocked as unsafe")
                .body("<p>blocked</p>").status(EmailLog.STATUS_FAILED).build();
        when(emailLogRepository.findById(32L)).thenReturn(Optional.of(failed));

        final var resent = sendingEmailService.resendEmail(32L);

        verify(emailService).sendEmail(any(Email.class), eq(null));
        verifyNoInteractions(settingsService);
        assertThat(resent.getStatus()).isEqualTo(EmailLog.STATUS_SENT);
        assertThat(failed.getStatus()).isEqualTo(EmailLog.STATUS_RESENT);
    }

    private static List<Url> domainBlockedLinks(final int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(number -> Url.builder().id((long) number).shortUrl("deal" + number).longUrl("https://jofogas.cards/deal/" + number)
                        .blockReason(BlockReason.DOMAIN_RULE).blockDetail("jofogas.cards").active(false).build())
                .toList();
    }

}
