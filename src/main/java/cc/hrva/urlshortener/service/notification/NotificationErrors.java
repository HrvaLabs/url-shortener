package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.client.PostResult;
import cc.hrva.urlshortener.model.enums.ChannelType;

public final class NotificationErrors {

    static final String REDIRECTED = "The channel redirected the request. Paste the final URL.";
    static final String TIMED_OUT = "Couldn't reach the channel: no answer within 10 seconds.";
    static final String NOT_ALLOWED = "Couldn't reach the channel: the address isn't a public https address any more.";
    static final String UNREACHABLE = "Couldn't reach the channel.";
    static final String TELEGRAM_TOKEN_REFUSED = "Telegram refused the bot token. Check it with @BotFather.";

    private NotificationErrors() {
    }

    public static String describe(final PostResult result, final ChannelType type) {
        if (result.delivered()) {
            return null;
        }
        if ("redirected".equals(result.failure()) || result.httpStatus() / 100 == 3) {
            return REDIRECTED;
        }
        if ("timeout".equals(result.failure())) {
            return TIMED_OUT;
        }
        if ("not-allowed".equals(result.failure())) {
            return NOT_ALLOWED;
        }
        if (result.httpStatus() == 0) {
            return UNREACHABLE;
        }
        if (type == ChannelType.TELEGRAM && (result.httpStatus() == 401 || result.httpStatus() == 403)) {
            return TELEGRAM_TOKEN_REFUSED;
        }
        return "The channel answered with HTTP %d. Check the URL.".formatted(result.httpStatus());
    }

    public static Integer httpStatus(final PostResult result) {
        return result.httpStatus() == 0 ? null : result.httpStatus();
    }

}
