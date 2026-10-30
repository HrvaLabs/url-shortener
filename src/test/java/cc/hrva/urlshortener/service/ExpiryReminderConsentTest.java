package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Email;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.impl.DefaultSendingEmailService;
import cc.hrva.urlshortener.service.impl.DefaultUrlNotificationService;
import cc.hrva.urlshortener.service.notification.ConsentEmails;
import cc.hrva.urlshortener.service.notification.EmailConsentReader;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.hibernate.SessionFactory;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = ExpiryReminderConsentTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.datasource.url=jdbc:h2:mem:expiry-reminder-consent;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class ExpiryReminderConsentTest {

    private static final NotificationType REMINDER = NotificationType.EXPIRY_REMINDER_EMAIL;

    @Autowired
    private UrlNotificationService urlNotificationService;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationConsentRepository notificationConsentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private DefaultSendingEmailService sendingEmailService;

    @MockitoBean
    private ConsentEmails consentEmails;

    @MockitoBean
    private AppProperties appProperties;

    @MockitoBean
    private TemplateEngine templateEngine;

    @BeforeEach
    void setUp() {
        when(appProperties.getUrlExpirationNotificationHours()).thenReturn(24L);
        when(sendingEmailService.tryToSendConsentedEmail(any(Email.class), anyLong(), eq(REMINDER))).thenReturn(Optional.empty());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from notification_consent");
        jdbcTemplate.update("delete from url");
        jdbcTemplate.update("delete from user_account");
    }

    @Test
    void aDeactivatedOwnerGetsNoReminder() {
        final var ana = consentingOwnerWithAnExpiringLink("ana@example.com", "anas-menu");
        final var bob = consentingOwnerWithAnExpiringLink("bob@example.com", "bobs-menu");
        jdbcTemplate.update("update user_account set active = false where id = ?", bob.getId());

        urlNotificationService.notifyExpiringUrls();

        verify(sendingEmailService).tryToSendConsentedEmail(any(Email.class), eq(ana.getId()), eq(REMINDER));
        verify(sendingEmailService, never()).tryToSendConsentedEmail(any(Email.class), eq(bob.getId()), any());
        assertThat(jdbcTemplate.queryForObject("select count(*) from notification_consent where user_id = ? and revoked_at is null",
                Long.class, bob.getId())).isOne();
    }

    @Test
    void aReactivatedOwnerGetsRemindersAgain() {
        final var bob = consentingOwnerWithAnExpiringLink("bob@example.com", "bobs-menu");
        jdbcTemplate.update("update user_account set active = false where id = ?", bob.getId());
        jdbcTemplate.update("update user_account set active = true where id = ?", bob.getId());

        urlNotificationService.notifyExpiringUrls();

        verify(sendingEmailService).tryToSendConsentedEmail(any(Email.class), eq(bob.getId()), eq(REMINDER));
    }

    @Test
    void expiringLinksArriveWithTheirOwnersWithoutAQueryPerOwner() {
        List.of("ana", "bob", "cid", "dora", "ema").forEach(name -> consentingOwnerWithAnExpiringLink(name + "@example.com", name + "-menu"));
        final var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        urlNotificationService.notifyExpiringUrls();

        verify(sendingEmailService, times(5)).tryToSendConsentedEmail(any(Email.class), anyLong(), eq(REMINDER));
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    private User consentingOwnerWithAnExpiringLink(final String email, final String alias) {
        final var owner = userRepository.saveAndFlush(User.builder().email(email).password("correct horse battery").apiKeySlots(4L).build());
        urlRepository.saveAndFlush(Url.builder().shortUrl(alias).longUrl("https://example.org/" + alias).owner(owner)
                .active(true).expirationDate(LocalDateTime.now().plusHours(2)).build());
        notificationConsentRepository.saveAndFlush(NotificationConsent.builder().owner(owner).type(REMINDER)
                .grantedAt(LocalDateTime.now().minusDays(1)).source(ConsentSource.SETTINGS).build());
        return owner;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UrlRepository.class, UserRepository.class, NotificationConsentRepository.class}))
    @Import({DefaultUrlNotificationService.class, EmailConsentReader.class, ShortLinks.class})
    static class TestApplication {
    }

}
