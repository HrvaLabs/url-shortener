package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.impl.DefaultEmailService;
import cc.hrva.urlshortener.service.impl.DefaultSendingEmailService;
import cc.hrva.urlshortener.service.notification.ConsentEmails;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thymeleaf.TemplateEngine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = ConsentedEmailForActiveAccountsTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:consented-email-active-accounts;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ConsentedEmailForActiveAccountsTest {

    private static final NotificationType WEEKLY = NotificationType.WEEKLY_SUMMARY_EMAIL;

    @Autowired
    private DefaultSendingEmailService sendingEmailService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationConsentRepository notificationConsentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private DefaultEmailService emailService;

    @MockitoBean
    private ConsentEmails consentEmails;

    @MockitoBean
    private AppProperties appProperties;

    @MockitoBean
    private TemplateEngine templateEngine;

    @MockitoBean
    private NotificationMessages notificationMessages;

    @MockitoBean
    private SettingsService settingsService;

    private User owner;

    @BeforeEach
    void setUp() {
        when(consentEmails.headers(anyLong(), any())).thenReturn(Map.of());
        owner = userRepository.saveAndFlush(User.builder().email("ana@example.com").password("correct horse battery").apiKeySlots(4L).build());
        notificationConsentRepository.saveAndFlush(NotificationConsent.builder().owner(owner).type(WEEKLY)
                .grantedAt(LocalDateTime.of(2026, 10, 20, 9, 0)).source(ConsentSource.SETTINGS).build());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from email_log");
        jdbcTemplate.update("delete from notification_consent");
        jdbcTemplate.update("delete from user_account");
    }

    @Test
    void aDeactivatedOwnersConsentedEmailIsNotSent() throws Exception {
        jdbcTemplate.update("update user_account set active = false where id = ?", owner.getId());

        assertThat(sendingEmailService.tryToSendConsentedEmail(weeklyEmail(), owner.getId(), WEEKLY)).isEmpty();

        verify(emailService, never()).sendEmail(any(Email.class), any());
        assertThat(jdbcTemplate.queryForObject("select count(*) from email_log", Long.class)).isZero();
    }

    @Test
    void anActiveOwnersConsentedEmailIsSentAndLogged() throws Exception {
        final var logged = sendingEmailService.tryToSendConsentedEmail(weeklyEmail(), owner.getId(), WEEKLY);

        assertThat(logged).map(EmailLog::getUserId).contains(owner.getId());
        verify(emailService).sendEmail(any(Email.class), any());
    }

    private static Email weeklyEmail() {
        return Email.builder().sender("noreply@hrva.cc").receivers(new String[]{"ana@example.com"}).subject("Your week").text("<p>hi</p>").build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UserRepository.class, NotificationConsentRepository.class, EmailLogRepository.class}))
    @Import({DefaultSendingEmailService.class, EmailConsentReader.class, ShortLinks.class})
    static class TestApplication {
    }

}
