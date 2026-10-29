package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.client.Resolver;
import cc.hrva.urlshortener.dto.notification.NotificationChannelRequest;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.util.PublicAddresses;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChannelTargets {

    public static final int MASK_LENGTH = 300;
    public static final String TARGET = "target";
    public static final String BOT_TOKEN = "botToken";
    public static final String CHAT_ID = "chatId";

    static final String PASTE_THE_URL = "Paste the URL";
    static final String USE_HTTPS = "Use an https URL.";
    static final String NOT_FOUND = "We couldn't find this address. Check the URL.";
    static final String NOT_PUBLIC = "This address isn't reachable from the internet. Use a public https URL.";
    static final String DISCORD_SHAPE = "Paste a Discord webhook URL. It starts with https://discord.com/api/webhooks/";
    static final String SLACK_SHAPE = "Paste a Slack webhook URL. It starts with https://hooks.slack.com/services/";
    static final String NTFY_SHAPE = "Paste your ntfy topic URL, like https://ntfy.sh/your-topic";
    static final String TELEGRAM_ADDRESS = "This is a Telegram address. Choose Telegram as the type and paste the bot token instead.";
    static final String BOT_TOKEN_SHAPE = "Paste the bot token from @BotFather, like 123456789:AAH4Fq2xYzExample";
    static final String CHAT_ID_SHAPE = "Enter the chat ID, a number like -1001234567890 or a channel name like @mychannel";

    private static final String HTTPS = "https";
    private static final String BULLETS = Character.toString(0x2022).repeat(4);
    private static final int VISIBLE_CHARACTERS = 4;
    private static final Set<String> DISCORD_HOSTS = Set.of("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com");
    private static final String SLACK_HOST = "hooks.slack.com";
    private static final String TELEGRAM_HOST = "api.telegram.org";
    private static final String NTFY_HOST = "ntfy.sh";
    private static final String DISCORD_PATH = "/api/webhooks/";
    private static final String SLACK_PATH = "/services/";
    private static final Pattern TELEGRAM_TOKEN = Pattern.compile("^\\d{5,}:[A-Za-z0-9_-]{30,}$");
    private static final Pattern TELEGRAM_CHAT_ID = Pattern.compile("^-?\\d{1,20}$|^@[A-Za-z][A-Za-z0-9_]{4,31}$");

    private final Resolver resolver;

    public Map<String, String> fieldErrors(final ChannelType type, final NotificationChannelRequest request, final boolean creating) {
        final var errors = new LinkedHashMap<String, String>();
        if (type == ChannelType.TELEGRAM) {
            shapeError(request.getBotToken(), TELEGRAM_TOKEN, BOT_TOKEN_SHAPE, creating).ifPresent(error -> errors.put(BOT_TOKEN, error));
            shapeError(request.getChatId(), TELEGRAM_CHAT_ID, CHAT_ID_SHAPE, creating).ifPresent(error -> errors.put(CHAT_ID, error));
            return errors;
        }
        if (StringUtils.isBlank(request.getTarget())) {
            if (creating) {
                errors.put(TARGET, PASTE_THE_URL);
            }
            return errors;
        }
        targetError(type, request.getTarget().strip()).ifPresent(error -> errors.put(TARGET, error));
        return errors;
    }

    public static Optional<ChannelType> detect(final String url) {
        return uriOf(url).flatMap(ChannelTargets::hostOf).flatMap(ChannelTargets::typeOfHost);
    }

    public static String mask(final ChannelType type, final String target) {
        final var lastFour = lastCharacters(target);
        if (type == ChannelType.TELEGRAM) {
            return "Bot " + BULLETS + lastFour;
        }
        final var uri = URI.create(target);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + "/" + BULLETS + lastFour;
    }

    public static String label(final ChannelType type) {
        return NotificationDescriptions.label(type);
    }

    private Optional<String> targetError(final ChannelType type, final String target) {
        final var host = httpsHost(target);
        if (host.isEmpty()) {
            return Optional.of(USE_HTTPS);
        }
        final var path = URI.create(target).getRawPath();
        final var shapeError = switch (type) {
            case DISCORD -> DISCORD_HOSTS.contains(host.get()) && StringUtils.startsWith(path, DISCORD_PATH) ? null : DISCORD_SHAPE;
            case SLACK -> SLACK_HOST.equals(host.get()) && StringUtils.startsWith(path, SLACK_PATH) ? null : SLACK_SHAPE;
            case NTFY -> StringUtils.isEmpty(StringUtils.removeStart(path, "/")) ? NTFY_SHAPE : null;
            case WEBHOOK -> typeOfHost(host.get()).map(ChannelTargets::chatHostRefusal).orElse(null);
            case TELEGRAM -> null;
        };
        return Optional.ofNullable(shapeError).or(() -> addressError(host.get()));
    }

    private Optional<String> addressError(final String host) {
        try {
            return PublicAddresses.arePublic(resolver.resolve(host)) ? Optional.empty() : Optional.of(NOT_PUBLIC);
        } catch (final UnknownHostException exception) {
            return Optional.of(NOT_FOUND);
        }
    }

    private static String chatHostRefusal(final ChannelType type) {
        return type == ChannelType.TELEGRAM
                ? TELEGRAM_ADDRESS
                : "This is a %s webhook. Choose %s as the type so messages arrive formatted.".formatted(label(type), label(type));
    }

    private static Optional<String> shapeError(final String value, final Pattern shape, final String error, final boolean creating) {
        if (StringUtils.isBlank(value)) {
            return creating ? Optional.of(error) : Optional.empty();
        }
        return shape.matcher(value.strip()).matches() ? Optional.empty() : Optional.of(error);
    }

    private static Optional<String> httpsHost(final String url) {
        return uriOf(url).filter(uri -> HTTPS.equalsIgnoreCase(uri.getScheme())).flatMap(ChannelTargets::hostOf);
    }

    private static Optional<URI> uriOf(final String url) {
        if (StringUtils.isBlank(url)) {
            return Optional.empty();
        }
        try {
            return Optional.of(URI.create(url.strip()));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> hostOf(final URI uri) {
        return Optional.ofNullable(uri.getHost())
                .map(host -> StringUtils.removeEnd(StringUtils.removeStart(host, "["), "]"))
                .filter(StringUtils::isNotEmpty)
                .map(host -> host.toLowerCase(Locale.ROOT));
    }

    private static Optional<ChannelType> typeOfHost(final String host) {
        if (DISCORD_HOSTS.contains(host)) {
            return Optional.of(ChannelType.DISCORD);
        }
        return switch (host) {
            case SLACK_HOST -> Optional.of(ChannelType.SLACK);
            case TELEGRAM_HOST -> Optional.of(ChannelType.TELEGRAM);
            case NTFY_HOST -> Optional.of(ChannelType.NTFY);
            default -> Optional.empty();
        };
    }

    private static String lastCharacters(final String value) {
        final var count = value.codePointCount(0, value.length());
        return value.substring(value.offsetByCodePoints(0, Math.max(0, count - VISIBLE_CHARACTERS)));
    }

}
