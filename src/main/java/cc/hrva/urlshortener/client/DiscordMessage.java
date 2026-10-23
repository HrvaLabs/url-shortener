package cc.hrva.urlshortener.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DiscordMessage(String content, @JsonProperty("allowed_mentions") AllowedMentions allowedMentions, int flags) {

    private static final int SUPPRESS_EMBEDS = 1 << 2;

    public static DiscordMessage withoutMentionsOrEmbeds(final String content) {
        return new DiscordMessage(content, new AllowedMentions(List.of()), SUPPRESS_EMBEDS);
    }

    public record AllowedMentions(List<String> parse) {}

}
