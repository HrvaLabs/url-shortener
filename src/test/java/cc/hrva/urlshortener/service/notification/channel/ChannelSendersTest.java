package cc.hrva.urlshortener.service.notification.channel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cc.hrva.urlshortener.client.ChannelPoster;
import cc.hrva.urlshortener.client.PostResult;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.NotificationMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelSendersTest {

    private static final String ARROW = Character.toString(0x2192);
    private static final String ZERO_WIDTH_SPACE = Character.toString(0x200B);
    private static final String DISCORD_URL = "https://discord.com/api/webhooks/123456/discord-SECRET-token";
    private static final String SLACK_URL = "https://hooks.slack.com/services/T0/B0/slack-SECRET-token";
    private static final String NTFY_URL = "https://ntfy.sh/ntfy-SECRET-topic";
    private static final String NTFY_TOKEN = "tk_ntfy-SECRET-access";
    private static final String WEBHOOK_URL = "https://hooks.example.org/webhook-SECRET-path";
    private static final String SIGNING_SECRET = "signing-SECRET-value";
    private static final String BOT_TOKEN = "123456789:AAH4Fq2xYz-bot-SECRET-token-AbCdEf";
    private static final String JSON_BODY = "{\"event\":\"link.dead\",\"deliveryId\":\"uid-1\",\"links\":[]}";
    private static final NotificationMessage MESSAGE = new NotificationMessage("hrva.cc: 1 link looks broken",
            List.of("hrva.cc/my_link " + ARROW + " a_b*c.example, since 27 October 2026, the page wasn't found (404)",
                    "Channel: <!channel> @everyone @here [x](https://evil.example)"),
            "https://app.hrva.cc/dashboard", 0);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<Posted> posted = new ArrayList<>();
    private final Logger appLogger = (Logger) LoggerFactory.getLogger("cc.hrva.urlshortener");
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private Level previousLevel;
    private PostResult answer = new PostResult(204, null);
    private RuntimeException failure;

    private ChannelSenders senders;

    @BeforeEach
    void setUp() {
        final ChannelPoster poster = (url, contentType, headers, body, timeout) -> {
            posted.add(new Posted(url, contentType, headers, body, timeout));
            if (failure != null) {
                throw failure;
            }
            return answer;
        };
        senders = new ChannelSenders(List.of(new DiscordChannelSender(jsonMapper), new SlackChannelSender(jsonMapper),
                new TelegramChannelSender(jsonMapper), new NtfyChannelSender(), new WebhookChannelSender()), poster);
        previousLevel = appLogger.getLevel();
        appLogger.setLevel(Level.DEBUG);
        logEvents.start();
        appLogger.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        appLogger.detachAppender(logEvents);
        appLogger.setLevel(previousLevel);
    }

    @Test
    void discordValuesAreEscaped() {
        final var result = senders.send(channel(ChannelType.DISCORD), new ChannelTarget(DISCORD_URL, null, null, null), "link.dead", "uid-1", JSON_BODY, MESSAGE);

        assertThat(result.delivered()).isTrue();
        final var request = onlyPost();
        assertThat(request.url()).isEqualTo(DISCORD_URL);
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(10));
        final var body = json(request);
        assertThat(body.get("allowed_mentions").get("parse").isArray()).isTrue();
        assertThat(body.get("allowed_mentions").get("parse").size()).isZero();
        assertThat(body.get("flags").asInt()).isEqualTo(4);
        assertThat(body.get("content").asString()).isEqualTo("""
                **hrva.cc: 1 link looks broken**
                hrva.cc/my\\_link %s a\\_b\\*c.example, since 27 October 2026, the page wasn't found (404)
                Channel: !channel @everyone @here x https://evil.example
                <https://app.hrva.cc/dashboard>""".formatted(ARROW).replace("x https://evil.example", "x <https://evil.example>"));
        assertThat(body.get("content").asString()).doesNotContain("<!channel>", "](", ZERO_WIDTH_SPACE);
    }

    @Test
    void discordStaysWithinTheLimitAfterEscaping() {
        final var lines = IntStream.range(0, 20).mapToObj(index -> "hrva.cc/" + "_".repeat(40) + index + " " + ARROW + " " + "*".repeat(40) + ".example").toList();
        final var message = new NotificationMessage("hrva.cc: 25 links look broken", lines, "https://app.hrva.cc/dashboard", 5);

        senders.send(channel(ChannelType.DISCORD), new ChannelTarget(DISCORD_URL, null, null, null), "link.dead", "uid-1", JSON_BODY, message);

        final var content = json(onlyPost()).get("content").asString();
        assertThat(content.length()).isLessThanOrEqualTo(1900);
        assertThat(content).matches("(?s).*\\nand \\d+ more: <https://app\\.hrva\\.cc/dashboard>$");
    }

    @Test
    void slackBodyIsEscapedText() {
        senders.send(channel(ChannelType.SLACK), new ChannelTarget(SLACK_URL, null, null, null), "link.dead", "uid-1", JSON_BODY, MESSAGE);

        final var request = onlyPost();
        assertThat(request.url()).isEqualTo(SLACK_URL);
        final var body = json(request);
        assertThat(body.propertyNames()).containsExactly("text");
        assertThat(body.get("text").asString()).isEqualTo("""
                hrva.cc: 1 link looks broken
                hrva.cc/my_link %s a_b*c.example, since 27 October 2026, the page wasn't found (404)
                Channel: &lt;!channel&gt; @everyone @%shere [x] (https://evil.example)
                https://app.hrva.cc/dashboard""".formatted(ARROW, ZERO_WIDTH_SPACE));
    }

    @Test
    void telegramPostsToTheBotUrlWithChatId() {
        senders.send(channel(ChannelType.TELEGRAM), new ChannelTarget(BOT_TOKEN, null, "-1001234567890", null), "link.dead", "uid-1", JSON_BODY, MESSAGE);

        final var request = onlyPost();
        assertThat(request.url()).isEqualTo("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage");
        assertThat(request.contentType()).isEqualTo("application/json");
        final var body = json(request);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("chat_id", "text", "disable_web_page_preview");
        assertThat(body.get("chat_id").asString()).isEqualTo("-1001234567890");
        assertThat(body.get("disable_web_page_preview").asBoolean()).isTrue();
        assertThat(body.has("parse_mode")).isFalse();
        assertThat(body.get("text").asString()).startsWith("hrva.cc: 1 link looks broken\nhrva.cc/my_link " + ARROW + " a_b*c.example")
                .endsWith("\nhttps://app.hrva.cc/dashboard");
    }

    @Test
    void ntfyIsPlainTextWithTitleAndClick() {
        senders.send(channel(ChannelType.NTFY), new ChannelTarget(NTFY_URL, null, null, null), "link.dead", "uid-1", JSON_BODY, MESSAGE);

        final var request = onlyPost();
        assertThat(request.url()).isEqualTo(NTFY_URL);
        assertThat(request.contentType()).isEqualTo("text/plain; charset=utf-8");
        assertThat(request.headers()).containsExactlyInAnyOrderEntriesOf(Map.of("Title", "hrva.cc", "Click", "https://app.hrva.cc/dashboard", "Tags", "link"));
        assertThat(new String(request.body(), StandardCharsets.UTF_8)).startsWith("hrva.cc: 1 link looks broken\n").endsWith("\nhttps://app.hrva.cc/dashboard");
    }

    @Test
    void ntfyTokenIsBearer() {
        senders.send(channel(ChannelType.NTFY), new ChannelTarget(NTFY_URL, NTFY_TOKEN, null, null), "link.dead", "uid-1", JSON_BODY, MESSAGE);

        assertThat(onlyPost().headers()).containsEntry("Authorization", "Bearer " + NTFY_TOKEN);
    }

    @Test
    void webhookIsSigned() throws Exception {
        senders.send(channel(ChannelType.WEBHOOK), new ChannelTarget(WEBHOOK_URL, null, null, SIGNING_SECRET), "link.dead", "uid-1", JSON_BODY, MESSAGE);

        final var request = onlyPost();
        assertThat(request.url()).isEqualTo(WEBHOOK_URL);
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo(JSON_BODY);
        final var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        final var expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(JSON_BODY.getBytes(StandardCharsets.UTF_8)));
        assertThat(request.headers()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "X-Hrva-Event", "link.dead", "X-Hrva-Delivery", "uid-1", "X-Hrva-Signature", expected));
    }

    @Test
    void theOutgoingRequestNeverPrintsItsAddressOrBody() {
        final var request = new OutgoingRequest(WEBHOOK_URL, "application/json", Map.of("X-Hrva-Event", "test"), JSON_BODY.getBytes(StandardCharsets.UTF_8));

        assertThat(request.toString()).doesNotContain(WEBHOOK_URL, "webhook-SECRET", "link.dead");
    }

    @Test
    void theAnswerComesBackAsData() {
        answer = new PostResult(404, null);

        final var result = senders.send(channel(ChannelType.DISCORD), new ChannelTarget(DISCORD_URL, null, null, null), "test", "test-1", JSON_BODY, MESSAGE);

        assertThat(result).isEqualTo(new PostResult(404, null));
    }

    @Test
    void aSenderThatThrowsIsAFailedPost() {
        failure = new IllegalArgumentException("bad header " + NTFY_TOKEN);

        final var result = senders.send(channel(ChannelType.NTFY), new ChannelTarget(NTFY_URL, NTFY_TOKEN, null, null), "test", "test-1", JSON_BODY, MESSAGE);

        assertThat(result).isEqualTo(new PostResult(0, "error"));
    }

    @Test
    void noTargetOrTokenInAnyLogLine() {
        final var targets = Map.of(
                ChannelType.DISCORD, new ChannelTarget(DISCORD_URL, null, null, null),
                ChannelType.SLACK, new ChannelTarget(SLACK_URL, null, null, null),
                ChannelType.TELEGRAM, new ChannelTarget(BOT_TOKEN, null, "-1001234567890", null),
                ChannelType.NTFY, new ChannelTarget(NTFY_URL, NTFY_TOKEN, null, null),
                ChannelType.WEBHOOK, new ChannelTarget(WEBHOOK_URL, null, null, SIGNING_SECRET));
        answer = new PostResult(404, null);
        targets.forEach((type, target) -> senders.send(channel(type), target, "link.dead", "uid-1", JSON_BODY, MESSAGE));
        answer = new PostResult(0, "timeout");
        targets.forEach((type, target) -> senders.send(channel(type), target, "link.dead", "uid-1", JSON_BODY, MESSAGE));
        failure = new IllegalStateException("failed for " + DISCORD_URL + " " + BOT_TOKEN + " " + NTFY_TOKEN + " " + SIGNING_SECRET);
        targets.forEach((type, target) -> senders.send(channel(type), target, "link.dead", "uid-1", JSON_BODY, MESSAGE));

        assertThat(posted).hasSize(15);
        assertThat(logEvents.list).isNotEmpty();
        for (final var event : logEvents.list) {
            final var everything = event.getFormattedMessage() + " " + Arrays.toString(event.getArgumentArray())
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            assertThat(everything).doesNotContain("SECRET", "123456789:", "hooks.example.org", "ntfy.sh", "discord.com", "hooks.slack.com");
        }
    }

    private Posted onlyPost() {
        assertThat(posted).hasSize(1);
        return posted.getFirst();
    }

    private JsonNode json(final Posted request) {
        return jsonMapper.readTree(new String(request.body(), StandardCharsets.UTF_8));
    }

    private static NotificationChannel channel(final ChannelType type) {
        return NotificationChannel.builder().id(4L).name("Team alerts").type(type).enabled(true).build();
    }

    private record Posted(String url, String contentType, Map<String, String> headers, byte[] body, Duration timeout) {
    }

}
