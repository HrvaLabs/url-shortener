package cc.hrva.urlshortener.service.notification.channel;

import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.NotificationMessage;
import cc.hrva.urlshortener.service.notification.NotificationMessages;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

@Component
public class NtfyChannelSender implements ChannelSender {

    private static final String PLAIN_TEXT = "text/plain; charset=utf-8";
    private static final int SPACE = 0x20;
    private static final int DELETE = 0x7F;
    private static final int MOST_WORD_BYTES = 45;

    @Override
    public ChannelType type() {
        return ChannelType.NTFY;
    }

    @Override
    public OutgoingRequest render(final ChannelTarget target, final String eventName, final String deliveryUid, final String json,
            final NotificationMessage message) {
        final var headers = new LinkedHashMap<String, String>();
        headers.put("Title", header(message.title()));
        if (message.link() != null) {
            headers.put("Click", message.link());
        }
        headers.put("Tags", "link");
        if (target.accessToken() != null) {
            headers.put("Authorization", "Bearer " + target.accessToken());
        }
        return new OutgoingRequest(target.target(), PLAIN_TEXT, headers,
                NotificationMessages.fitted(message, NotificationMessages.Format.WITHOUT_TITLE).getBytes(StandardCharsets.UTF_8));
    }

    private static String header(final String title) {
        if (title.chars().allMatch(character -> character >= SPACE && character < DELETE)) {
            return title;
        }
        final var words = new ArrayList<String>();
        var word = new StringBuilder();
        var bytes = 0;
        for (final var codePoint : title.codePoints().toArray()) {
            final var character = Character.toString(codePoint);
            final var width = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > MOST_WORD_BYTES) {
                words.add(encoded(word.toString()));
                word = new StringBuilder();
                bytes = 0;
            }
            word.append(character);
            bytes += width;
        }
        words.add(encoded(word.toString()));
        return String.join(" ", words);
    }

    private static String encoded(final String text) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

}
