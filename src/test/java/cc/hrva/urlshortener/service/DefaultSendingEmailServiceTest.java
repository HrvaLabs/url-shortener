package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.DecisionsDigest;
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
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSendingEmailServiceTest {

    private static final User OWNER = User.builder().email("alice@example.com").apiKeySlots(4L).build();
    private static final Url BLOCKED_LINK = Url.builder().shortUrl("promo").longUrl("https://jofogas.cards/login")
            .blockReason(BlockReason.BLOCKLIST).blockDetail("HaGeZi Fake").active(false).build();
    private static final LocalDateTime PASSKEY_ADDED_AT = LocalDateTime.of(2026, 10, 25, 14, 5, 33);

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

    private DefaultSendingEmailService configuredEmailService(final TemplateEngine engine) {
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

    private static DecisionsDigest decisions(final long openCount) {
        return new DecisionsDigest(openCount, 0, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static DecisionsDigest everything(final String userText) {
        return new DecisionsDigest(
                3,
                2,
                List.of(
                        new LinkDigestEntry(41L, "njuskaio_verify" + userText, "njuskaio.cam/a/MAUB3" + userText, "raulsado50w@gmail.com" + userText,
                                List.of("imitates njuskalo" + userText, "reported by a visitor: phishing")),
                        new LinkDigestEntry(42L, "v0DB3", "findfavoritedate.life", null, List.of("high-risk domain ending .life"))),
                List.of(new DecisionsDigest.PossibleScammer("scammer@example.com" + userText, 1, 2)),
                List.of(new DecisionsDigest.BlockedLink("promo" + userText, "www.jofogas.cards/login" + userText, "a blocklist (HaGeZi Fake" + userText + ")")),
                List.of(new DecisionsDigest.FailingSource("HaGeZi Fake" + userText, "HTTP 503 from every mirror" + userText)),
                List.of(new DecisionsDigest.NewAccountLink("fresh" + userText, "example.com/fresh" + userText, "newcomer@example.com" + userText)));
    }

    private Email sentPasskeyAddedEmail(final String label) throws Exception {
        saveReturnsArgument();
        final var sent = ArgumentCaptor.forClass(Email.class);
        doAnswer(invocation -> null).when(emailService).sendEmail(sent.capture(), eq(null));

        configuredEmailService(realTemplateEngine()).sendPasskeyAddedEmail(OWNER, label, PASSKEY_ADDED_AT);

        return sent.getValue();
    }

    private static String textOf(final String html) {
        return HtmlUtils.htmlUnescape(html.replaceAll("(?s)<head>.*</head>", "").replaceAll("<[^>]+>", "")).replaceAll("\\s+", " ").trim();
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
                .contains("Links from accounts younger than 7 days", "hrva.cc/fresh", "example.com/fresh", "newcomer@example.com");
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
                .contains("newcomer@example.com&lt;script&gt;");
    }

    @Test
    void aFailingSourceOnItsOwnShowsItsNameAndErrorAndLinksToTheBlocklists() throws Exception {
        final var email = sentDecisionsEmail(new DecisionsDigest(0, 0, List.of(), List.of(), List.of(),
                List.of(new DecisionsDigest.FailingSource("HaGeZi Fake", "HTTP 503 from every mirror")), List.of()));

        assertThat(email.getSubject()).isEqualTo("No decisions waiting on hrva.cc, but there's news");
        assertThat(email.getText()).contains("HaGeZi Fake", "HTTP 503 from every mirror");
        assertThat(linksIn(email.getText())).containsExactly("https://hrva.cc/admin/blocklists");
    }

    @Test
    void visitorReportsAreMentionedOnlyWhenThereAreAny() throws Exception {
        final var body = sentDecisionsEmail(decisions(2)).getText();

        assertThat(body).doesNotContain("reported by visitors");
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
                .containsExactly("https://hrva.cc/dashboard/settings", "mailto:support@hrva.cc", "https://hrva.cc/help/passkeys");
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
        verify(emailService).sendEmail(any(Email.class), eq(null));
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

    private static List<Url> domainBlockedLinks(final int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(number -> Url.builder().id((long) number).shortUrl("deal" + number).longUrl("https://jofogas.cards/deal/" + number)
                        .blockReason(BlockReason.DOMAIN_RULE).blockDetail("jofogas.cards").active(false).build())
                .toList();
    }

}
