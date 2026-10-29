package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.enums.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentEmailsTest {

    private final UnsubscribeTokens tokens = new UnsubscribeTokens(NotificationTestKeys.keys());

    private ConsentEmails consentEmails;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc/");
        appProperties.setFrontendUrl("https://app.hrva.cc/");
        consentEmails = new ConsentEmails(tokens, appProperties);
    }

    @Test
    void headersPointOneClickClientsAtTheBackend() {
        final var token = tokens.issue(42L, NotificationType.WEEKLY_SUMMARY_EMAIL);

        assertThat(consentEmails.headers(42L, NotificationType.WEEKLY_SUMMARY_EMAIL))
                .containsExactly(
                        org.assertj.core.api.Assertions.entry("List-Unsubscribe", "<https://hrva.cc/api/v1/notifications/unsubscribe?token=" + token + ">"),
                        org.assertj.core.api.Assertions.entry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
    }

    @Test
    void theVisibleLinkGoesToTheConfirmPage() {
        final var token = tokens.issue(42L, NotificationType.EXPIRY_REMINDER_EMAIL);

        assertThat(consentEmails.unsubscribePage(42L, NotificationType.EXPIRY_REMINDER_EMAIL))
                .isEqualTo("https://app.hrva.cc/unsubscribe?token=" + token);
    }

    @Test
    void theSettingsLinkOpensTheNotificationsCard() {
        assertThat(consentEmails.settingsPage()).isEqualTo("https://app.hrva.cc/dashboard/settings#notifications");
    }

    @Test
    void theTokenInBothUrlsIsUrlSafe() {
        final var header = consentEmails.headers(Long.MAX_VALUE, NotificationType.DEAD_LINK_EMAIL).get("List-Unsubscribe");
        final var page = consentEmails.unsubscribePage(Long.MAX_VALUE, NotificationType.DEAD_LINK_EMAIL);

        assertThat(header).matches("^<https://hrva\\.cc/api/v1/notifications/unsubscribe\\?token=[A-Za-z0-9_.-]+>$");
        assertThat(page).matches("^https://app\\.hrva\\.cc/unsubscribe\\?token=[A-Za-z0-9_.-]+$");
        assertThat(tokens.read(page.substring(page.indexOf("token=") + "token=".length())))
                .contains(new UnsubscribeTarget(Long.MAX_VALUE, NotificationType.DEAD_LINK_EMAIL));
    }

    @Test
    void channelEventsHaveNoUnsubscribeLink() {
        assertThatThrownBy(() -> consentEmails.headers(42L, NotificationType.LINK_DEAD)).isInstanceOf(IllegalArgumentException.class);
    }

}
