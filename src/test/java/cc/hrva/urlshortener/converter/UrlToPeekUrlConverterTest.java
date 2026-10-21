package cc.hrva.urlshortener.converter;

import java.time.LocalDateTime;
import cc.hrva.urlshortener.model.PeekUrl;
import cc.hrva.urlshortener.model.Url;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlToPeekUrlConverterTest {

    private final UrlToPeekUrlConverter converter = new UrlToPeekUrlConverter();

    @Test
    void shouldConvertUrlToPeekUrl() {
        final var createDate = LocalDateTime.now();
        final var url = Url.builder()
                .longUrl("https://example.com")
                .shortUrl("ex")
                .createDate(createDate)
                .build();

        final var peekUrl = converter.convert(url);

        assertThat(peekUrl.getLongUrl()).isEqualTo("https://example.com");
        assertThat(peekUrl.getShortUrl()).isEqualTo("ex");
        assertThat(peekUrl.getCreateDate()).isEqualTo(createDate);
    }

    @Test
    void hidesTheDestinationOfAPasswordProtectedLink() {
        final var url = Url.builder().longUrl("https://secret.example/board").shortUrl("board").passwordHash("$2a$10$hash").build();

        final var peekUrl = converter.convert(url);

        assertThat(peekUrl.getLongUrl()).isNull();
        assertThat(peekUrl.isPasswordProtected()).isTrue();
        assertThat(peekUrl.getShortUrl()).isEqualTo("board");
    }

    @Test
    void shouldConvertWithNullValues() {
        final var url = Url.builder().build();

        final var peekUrl = converter.convert(url);

        assertThat(peekUrl.getLongUrl()).isNull();
        assertThat(peekUrl.getShortUrl()).isNull();
        assertThat(peekUrl.getCreateDate()).isNull();
    }
}
