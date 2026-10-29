package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.client.Resolver;
import cc.hrva.urlshortener.dto.notification.NotificationChannelRequest;
import cc.hrva.urlshortener.model.enums.ChannelType;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelTargetsTest {

    private static final String BULLETS = Character.toString(0x2022).repeat(4);
    private static final String PASTE_THE_URL = "Paste the URL";
    private static final String USE_HTTPS = "Use an https URL.";
    private static final String NOT_FOUND = "We couldn't find this address. Check the URL.";
    private static final String NOT_PUBLIC = "This address isn't reachable from the internet. Use a public https URL.";
    private static final String DISCORD_SHAPE = "Paste a Discord webhook URL. It starts with https://discord.com/api/webhooks/";
    private static final String SLACK_SHAPE = "Paste a Slack webhook URL. It starts with https://hooks.slack.com/services/";
    private static final String NTFY_SHAPE = "Paste your ntfy topic URL, like https://ntfy.sh/your-topic";
    private static final String BOT_TOKEN_SHAPE = "Paste the bot token from @BotFather, like 123456789:AAH4Fq2xYzExample";
    private static final String CHAT_ID_SHAPE = "Enter the chat ID, a number like -1001234567890 or a channel name like @mychannel";
    private static final String BOT_TOKEN = "123456789:AAH4Fq2xYzAbCdEfGhIjKlMnOpQrStUvWxYz";
    private static final String PUBLIC_ADDRESS = "162.159.128.233";

    private final List<String> lookedUp = new ArrayList<>();
    private final ChannelTargets channelTargets = new ChannelTargets(fakeDns());

    @Test
    void aValidTargetOfEachTypeHasNoErrors() {
        assertThat(errors(ChannelType.DISCORD, "https://discord.com/api/webhooks/123/abc")).isEmpty();
        assertThat(errors(ChannelType.DISCORD, "https://canary.discord.com/api/webhooks/123/abc")).isEmpty();
        assertThat(errors(ChannelType.DISCORD, "https://ptb.discord.com/api/webhooks/123/abc")).isEmpty();
        assertThat(errors(ChannelType.DISCORD, "https://discordapp.com/api/webhooks/123/abc")).isEmpty();
        assertThat(errors(ChannelType.SLACK, "https://hooks.slack.com/services/T0/B0/xyz")).isEmpty();
        assertThat(errors(ChannelType.NTFY, "https://ntfy.sh/my-secret-topic")).isEmpty();
        assertThat(errors(ChannelType.NTFY, "https://ntfy.example.org/alerts")).isEmpty();
        assertThat(errors(ChannelType.WEBHOOK, "https://hooks.example.org/hrva")).isEmpty();
        assertThat(telegram(BOT_TOKEN, "-1001234567890", true)).isEmpty();
        assertThat(telegram(BOT_TOKEN, "@mychannel", true)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ChannelType.class, names = {"DISCORD", "SLACK", "NTFY", "WEBHOOK"})
    void aMissingTargetOnCreateAsksForTheUrl(final ChannelType type) {
        assertThat(errors(type, null)).containsExactly(Map.entry("target", PASTE_THE_URL));
        assertThat(errors(type, "   ")).containsExactly(Map.entry("target", PASTE_THE_URL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://discord.com/api/webhooks/1/abc", "discord.com/api/webhooks/1/abc", "ftp://discord.com/api/webhooks/1/abc",
            "https://", "not a url", "https:///api/webhooks/1/abc"})
    void onlyHttpsIsTaken(final String target) {
        assertThat(errors(ChannelType.DISCORD, target)).containsExactly(Map.entry("target", USE_HTTPS));
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void anAddressThatDoesNotResolveIsNotFound() {
        assertThat(errors(ChannelType.WEBHOOK, "https://nowhere.example/hook")).containsExactly(Map.entry("target", NOT_FOUND));
        assertThat(errors(ChannelType.WEBHOOK, "https://hooks.example.org/hook")).isEmpty();
    }

    @Test
    void aNonPublicAddressIsRefused() {
        assertThat(errors(ChannelType.WEBHOOK, "https://internal.example/hook")).containsExactly(Map.entry("target", NOT_PUBLIC));
        assertThat(errors(ChannelType.NTFY, "https://127.0.0.1/topic")).containsExactly(Map.entry("target", NOT_PUBLIC));
        assertThat(errors(ChannelType.WEBHOOK, "https://[::1]/hook")).containsExactly(Map.entry("target", NOT_PUBLIC));
        assertThat(lookedUp).contains("internal.example", "127.0.0.1", "::1");
    }

    @Test
    void discordTakesOnlyItsWebhookUrls() {
        assertThat(errors(ChannelType.DISCORD, "https://discord.com/channels/1/2")).containsExactly(Map.entry("target", DISCORD_SHAPE));
        assertThat(errors(ChannelType.DISCORD, "https://hooks.slack.com/services/T0/B0/xyz")).containsExactly(Map.entry("target", DISCORD_SHAPE));
        assertThat(errors(ChannelType.DISCORD, "https://discord.com.evil.example/api/webhooks/1/abc"))
                .containsExactly(Map.entry("target", DISCORD_SHAPE));
        assertThat(errors(ChannelType.DISCORD, "https://DISCORD.com/api/webhooks/1/abc")).isEmpty();
    }

    @Test
    void slackTakesOnlyItsWebhookUrls() {
        assertThat(errors(ChannelType.SLACK, "https://hooks.slack.com/workflows/1")).containsExactly(Map.entry("target", SLACK_SHAPE));
        assertThat(errors(ChannelType.SLACK, "https://slack.com/services/T0/B0/xyz")).containsExactly(Map.entry("target", SLACK_SHAPE));
        assertThat(errors(ChannelType.SLACK, "https://discord.com/api/webhooks/1/abc")).containsExactly(Map.entry("target", SLACK_SHAPE));
    }

    @Test
    void ntfyNeedsATopic() {
        assertThat(errors(ChannelType.NTFY, "https://ntfy.sh")).containsExactly(Map.entry("target", NTFY_SHAPE));
        assertThat(errors(ChannelType.NTFY, "https://ntfy.sh/")).containsExactly(Map.entry("target", NTFY_SHAPE));
        assertThat(errors(ChannelType.NTFY, "https://ntfy.sh/alerts")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://discord.com/api/webhooks/1/abc|This is a Discord webhook. Choose Discord as the type so messages arrive formatted.",
            "https://canary.discord.com/api/webhooks/1/abc|This is a Discord webhook. Choose Discord as the type so messages arrive formatted.",
            "https://hooks.slack.com/services/T0/B0/xyz|This is a Slack webhook. Choose Slack as the type so messages arrive formatted.",
            "https://ntfy.sh/alerts|This is a ntfy webhook. Choose ntfy as the type so messages arrive formatted.",
            "https://api.telegram.org/bot123:abc/sendMessage|This is a Telegram address. Choose Telegram as the type and paste the bot token instead."
    })
    void webhookPointedAtAChatHostIsRefused(final String target, final String error) {
        assertThat(errors(ChannelType.WEBHOOK, target)).containsExactly(Map.entry("target", error));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123456789", "1234:AAH4Fq2xYzAbCdEfGhIjKlMnOpQrStUvWxYz", "123456789:short", "123456789:AAH4Fq2xYz AbCdEfGhIjKlMnOpQrStUvWxYz",
            "abc:AAH4Fq2xYzAbCdEfGhIjKlMnOpQrStUvWxYz"})
    void telegramTokenMustHaveItsShape(final String token) {
        assertThat(telegram(token, "-1001234567890", true)).containsExactly(Map.entry("botToken", BOT_TOKEN_SHAPE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "chat", "@abc", "@1channel", "123456789012345678901", "-", "@my-channel", "12 34"})
    void telegramChatIdMustHaveItsShape(final String chatId) {
        assertThat(telegram(BOT_TOKEN, chatId, true)).containsExactly(Map.entry("chatId", CHAT_ID_SHAPE));
    }

    @Test
    void telegramReportsBothFieldsAtOnce() {
        assertThat(telegram(null, null, true)).containsOnly(Map.entry("botToken", BOT_TOKEN_SHAPE), Map.entry("chatId", CHAT_ID_SHAPE));
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void editWithoutSecretsIsValid() {
        for (final var type : List.of(ChannelType.DISCORD, ChannelType.SLACK, ChannelType.NTFY, ChannelType.WEBHOOK)) {
            assertThat(channelTargets.fieldErrors(type, request(type).build(), false)).isEmpty();
        }
        assertThat(telegram(null, null, false)).isEmpty();
        assertThat(telegram("", " ", false)).isEmpty();
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void aNewTargetOnEditIsCheckedLikeOnCreate() {
        assertThat(channelTargets.fieldErrors(ChannelType.DISCORD, request(ChannelType.DISCORD).target("http://discord.com/api/webhooks/1/a").build(), false))
                .containsExactly(Map.entry("target", USE_HTTPS));
        assertThat(telegram("nope", null, false)).containsExactly(Map.entry("botToken", BOT_TOKEN_SHAPE));
    }

    @Test
    void surroundingSpacesAreIgnored() {
        assertThat(errors(ChannelType.DISCORD, "  https://discord.com/api/webhooks/123/abc  ")).isEmpty();
        assertThat(telegram(" " + BOT_TOKEN + " ", " @mychannel ", true)).isEmpty();
    }

    @Test
    void detectRecognisesTheFourHosts() {
        assertThat(ChannelTargets.detect("https://discord.com/api/webhooks/1/abc")).contains(ChannelType.DISCORD);
        assertThat(ChannelTargets.detect("https://discordapp.com/api/webhooks/1/abc")).contains(ChannelType.DISCORD);
        assertThat(ChannelTargets.detect("https://ptb.discord.com/api/webhooks/1/abc")).contains(ChannelType.DISCORD);
        assertThat(ChannelTargets.detect("https://canary.discord.com/api/webhooks/1/abc")).contains(ChannelType.DISCORD);
        assertThat(ChannelTargets.detect("https://Discord.COM:8443/x")).contains(ChannelType.DISCORD);
        assertThat(ChannelTargets.detect("https://hooks.slack.com/services/T0/B0/xyz")).contains(ChannelType.SLACK);
        assertThat(ChannelTargets.detect("https://api.telegram.org/bot1:a/sendMessage")).contains(ChannelType.TELEGRAM);
        assertThat(ChannelTargets.detect("https://ntfy.sh/alerts")).contains(ChannelType.NTFY);
    }

    @Test
    void detectIgnoresLookAlikes() {
        assertThat(ChannelTargets.detect("https://discord.com.evil.example/api/webhooks/1/abc")).isEmpty();
        assertThat(ChannelTargets.detect("https://www.discord.com/api/webhooks/1/abc")).isEmpty();
        assertThat(ChannelTargets.detect("https://evil.example/discord.com")).isEmpty();
        assertThat(ChannelTargets.detect("https://slack.com/services/x")).isEmpty();
        assertThat(ChannelTargets.detect("https://ntfy.example.org/alerts")).isEmpty();
        assertThat(ChannelTargets.detect("not a url")).isEmpty();
        assertThat(ChannelTargets.detect(null)).isEqualTo(Optional.empty());
    }

    @Test
    void masksShowSchemeHostAndLastFour() {
        assertThat(ChannelTargets.mask(ChannelType.DISCORD, "https://discord.com/api/webhooks/1/abcdefgh")).isEqualTo("https://discord.com/" + BULLETS + "efgh");
        assertThat(ChannelTargets.mask(ChannelType.TELEGRAM, "123456:AAAAwxyz")).isEqualTo("Bot " + BULLETS + "wxyz");
        assertThat(ChannelTargets.mask(ChannelType.NTFY, "https://ntfy.sh/my-secret-topic")).isEqualTo("https://ntfy.sh/" + BULLETS + "opic");
        assertThat(ChannelTargets.mask(ChannelType.WEBHOOK, "https://Hooks.Example.org:8443/a/b?token=wxyz")).isEqualTo("https://hooks.example.org/" + BULLETS + "wxyz");
    }

    @Test
    void aMaskNeverSplitsACharacter() {
        final var smile = Character.toString(0x1F600);
        final var mask = ChannelTargets.mask(ChannelType.NTFY, "https://ntfy.sh/topic" + smile + smile + smile + smile);

        assertThat(mask).isEqualTo("https://ntfy.sh/" + BULLETS + smile.repeat(4));
    }

    @Test
    void theLongestHostFitsTheMaskColumn() {
        final var label = "a".repeat(63);
        final var host = String.join(".", label, label, label, "b".repeat(58)) + ".hr";
        final var mask = ChannelTargets.mask(ChannelType.WEBHOOK, "https://" + host + "/hook/abcd");

        assertThat(host).hasSize(253);
        assertThat(mask).isEqualTo("https://" + host + "/" + BULLETS + "abcd");
        assertThat(mask.length()).isLessThanOrEqualTo(ChannelTargets.MASK_LENGTH);
    }

    @Test
    void labelsNameEachType() {
        assertThat(List.of(ChannelType.values())).extracting(ChannelTargets::label)
                .containsExactly("Discord", "Slack", "Telegram", "ntfy", "Webhook");
    }

    private Map<String, String> errors(final ChannelType type, final String target) {
        return channelTargets.fieldErrors(type, request(type).target(target).build(), true);
    }

    private Map<String, String> telegram(final String botToken, final String chatId, final boolean creating) {
        return channelTargets.fieldErrors(ChannelType.TELEGRAM, request(ChannelType.TELEGRAM).botToken(botToken).chatId(chatId).build(), creating);
    }

    private static NotificationChannelRequest.NotificationChannelRequestBuilder request(final ChannelType type) {
        return NotificationChannelRequest.builder().name("Team alerts").type(type);
    }

    private Resolver fakeDns() {
        return host -> {
            lookedUp.add(host);
            return switch (host) {
                case "nowhere.example" -> throw new UnknownHostException(host);
                case "internal.example" -> new InetAddress[] {InetAddress.ofLiteral("10.0.0.5")};
                case "127.0.0.1", "::1" -> new InetAddress[] {InetAddress.ofLiteral(host)};
                default -> new InetAddress[] {InetAddress.ofLiteral(PUBLIC_ADDRESS)};
            };
        };
    }

}
