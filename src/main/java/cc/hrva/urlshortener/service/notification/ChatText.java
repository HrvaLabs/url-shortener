package cc.hrva.urlshortener.service.notification;

import java.util.regex.Pattern;

public final class ChatText {

    private static final Pattern GROUP_MENTION = Pattern.compile("(?<!\\w)@(channel|all|here)(?!\\w)", Pattern.CASE_INSENSITIVE);
    private static final String ZERO_WIDTH_SPACE = Character.toString(0x200B);

    private ChatText() {
    }

    public static String mentionsDefused(final String text) {
        return GROUP_MENTION.matcher(text).replaceAll("@" + ZERO_WIDTH_SPACE + "$1");
    }

    public static String textDefused(final String text) {
        return mentionsDefused(text).replace("](", "] (");
    }

    public static String slackEscaped(final String text) {
        return textDefused(text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

}
