package cc.hrva.urlshortener.service.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTextTest {

    private static final String ZERO_WIDTH_SPACE = Character.toString(0x200B);

    @Test
    void everyoneIsLeftToDiscordsAllowedMentions() {
        assertThat(ChatText.mentionsDefused("@everyone look")).isEqualTo("@everyone look");
    }

    @Test
    void groupMentionsGetAZeroWidthSpaceInAnyCase() {
        assertThat(ChatText.mentionsDefused("@channel @HERE @all, @Here."))
                .isEqualTo("@" + ZERO_WIDTH_SPACE + "channel @" + ZERO_WIDTH_SPACE + "HERE @" + ZERO_WIDTH_SPACE + "all, @" + ZERO_WIDTH_SPACE + "Here.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"@allison", "x@here", "ana@example.com", "@channels", "mail@all.example", "@herewith"})
    void wordsThatOnlyLookLikeMentionsAreLeftAlone(final String text) {
        assertThat(ChatText.mentionsDefused(text)).isEqualTo(text);
    }

    @Test
    void markdownLinksAreBroken() {
        assertThat(ChatText.textDefused("[a](b)")).isEqualTo("[a] (b)");
        assertThat(ChatText.textDefused("[@all](https://evil.example)")).isEqualTo("[@" + ZERO_WIDTH_SPACE + "all] (https://evil.example)");
    }

    @Test
    void slackControlCharactersBecomeEntities() {
        assertThat(ChatText.slackEscaped("<!channel> & <x>")).isEqualTo("&lt;!channel&gt; &amp; &lt;x&gt;");
        assertThat(ChatText.slackEscaped("<https://evil.example|words>")).isEqualTo("&lt;https://evil.example|words&gt;");
        assertThat(ChatText.slackEscaped("@here [a](b) &amp;")).isEqualTo("@" + ZERO_WIDTH_SPACE + "here [a] (b) &amp;amp;");
    }

}
