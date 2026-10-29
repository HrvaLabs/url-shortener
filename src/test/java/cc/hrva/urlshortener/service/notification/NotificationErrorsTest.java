package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.client.PostResult;
import cc.hrva.urlshortener.model.enums.ChannelType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationErrorsTest {

    @Test
    void aDeliveryHasNoError() {
        assertThat(NotificationErrors.describe(new PostResult(204, null), ChannelType.DISCORD)).isNull();
        assertThat(NotificationErrors.describe(new PostResult(200, null), ChannelType.TELEGRAM)).isNull();
    }

    @Test
    void anAnswerOutside2xxNamesItsCode() {
        assertThat(NotificationErrors.describe(new PostResult(404, null), ChannelType.WEBHOOK)).isEqualTo("The channel answered with HTTP 404. Check the URL.");
        assertThat(NotificationErrors.describe(new PostResult(400, null), ChannelType.DISCORD)).isEqualTo("The channel answered with HTTP 400. Check the URL.");
        assertThat(NotificationErrors.describe(new PostResult(500, null), ChannelType.WEBHOOK)).isEqualTo("The channel answered with HTTP 500. Check the URL.");
        assertThat(NotificationErrors.describe(new PostResult(401, null), ChannelType.NTFY)).isEqualTo("The channel answered with HTTP 401. Check the URL.");
        assertThat(NotificationErrors.describe(new PostResult(400, null), ChannelType.TELEGRAM)).isEqualTo("The channel answered with HTTP 400. Check the URL.");
    }

    @Test
    void aDeletedDiscordOrSlackWebhookSaysSo() {
        assertThat(NotificationErrors.describe(new PostResult(404, null), ChannelType.DISCORD)).isEqualTo("Discord doesn't know this webhook. It may have been deleted. Copy the webhook URL again.");
        assertThat(NotificationErrors.describe(new PostResult(410, null), ChannelType.DISCORD)).isEqualTo("Discord doesn't know this webhook. It may have been deleted. Copy the webhook URL again.");
        assertThat(NotificationErrors.describe(new PostResult(404, null), ChannelType.SLACK)).isEqualTo("Slack doesn't know this webhook. It may have been deleted. Copy the webhook URL again.");
        assertThat(NotificationErrors.describe(new PostResult(410, null), ChannelType.SLACK)).isEqualTo("Slack doesn't know this webhook. It may have been deleted. Copy the webhook URL again.");
        assertThat(NotificationErrors.describe(new PostResult(404, null), ChannelType.NTFY)).isEqualTo("The channel answered with HTTP 404. Check the URL.");
    }

    @Test
    void telegramRefusingTheTokenSaysSo() {
        assertThat(NotificationErrors.describe(new PostResult(401, null), ChannelType.TELEGRAM)).isEqualTo("Telegram refused the bot token. Check it with @BotFather.");
        assertThat(NotificationErrors.describe(new PostResult(403, null), ChannelType.TELEGRAM)).isEqualTo("Telegram refused the bot token. Check it with @BotFather.");
    }

    @Test
    void transportFailuresAreWorded() {
        assertThat(NotificationErrors.describe(new PostResult(302, "redirected"), ChannelType.WEBHOOK)).isEqualTo("The channel redirected the request. Paste the final URL.");
        assertThat(NotificationErrors.describe(new PostResult(0, "timeout"), ChannelType.SLACK)).isEqualTo("Couldn't reach the channel: no answer within 10 seconds.");
        assertThat(NotificationErrors.describe(new PostResult(0, "not-allowed"), ChannelType.NTFY))
                .isEqualTo("Couldn't reach the channel: the address isn't a public https address any more.");
        assertThat(NotificationErrors.describe(new PostResult(0, "error"), ChannelType.DISCORD)).isEqualTo("Couldn't reach the channel.");
        assertThat(NotificationErrors.describe(new PostResult(0, "invalid"), ChannelType.NTFY))
                .isEqualTo("Couldn't send to the channel: a saved setting isn't valid. Edit the channel and save it again.");
        assertThat(NotificationErrors.describe(new PostResult(0, null), ChannelType.DISCORD)).isEqualTo("Couldn't reach the channel.");
    }

    @Test
    void noAnswerHasNoStatus() {
        assertThat(NotificationErrors.httpStatus(new PostResult(0, "timeout"))).isNull();
        assertThat(NotificationErrors.httpStatus(new PostResult(404, null))).isEqualTo(404);
    }

}
