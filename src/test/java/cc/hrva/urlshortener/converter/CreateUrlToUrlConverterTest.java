package cc.hrva.urlshortener.converter;

import java.time.LocalDateTime;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.model.Url;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateUrlToUrlConverterTest {

    private final CreateUrlToUrlConverter converter = new CreateUrlToUrlConverter();

    @Test
    void shouldConvertCreateUrlDtoToUrl() {
        final var createUrlDto = CreateUrlDto.builder()
                .longUrl("https://example.com")
                .shortUrl("ex")
                .visitLimit(100L)
                .expirationDate(LocalDateTime.now().plusDays(1))
                .build();

        final var url = converter.convert(createUrlDto);

        assertThat(url.getLongUrl()).isEqualTo("https://example.com");
        assertThat(url.getShortUrl()).isEqualTo("ex");
        assertThat(url.getVisitLimit()).isEqualTo(100L);
        assertThat(url.getExpirationDate()).isEqualTo(createUrlDto.getExpirationDate());
    }

    @Test
    void shouldConvertWithNullValues() {
        final var createUrlDto = CreateUrlDto.builder()
                .longUrl("https://example.com")
                .build();

        final var url = converter.convert(createUrlDto);

        assertThat(url.getLongUrl()).isEqualTo("https://example.com");
        assertThat(url.getShortUrl()).isNull();
        assertThat(url.getVisitLimit()).isNull();
        assertThat(url.getExpirationDate()).isNull();
        assertThat(url.getTags()).isEmpty();
    }

    @Test
    void shouldNormalizeTags() {
        final var createUrlDto = CreateUrlDto.builder()
                .longUrl("https://example.com")
                .tags(new java.util.LinkedHashSet<>(java.util.List.of("Work", " work ", "BIG")))
                .build();

        final var url = converter.convert(createUrlDto);

        assertThat(url.getTags()).containsExactlyInAnyOrder("work", "big");
    }

    @Test
    void shouldPrependHttpsWhenSchemeIsMissing() {
        assertThat(convertLongUrl("example.com")).isEqualTo("https://example.com");
    }

    @Test
    void shouldPrependHttpsWhenHostHasPort() {
        assertThat(convertLongUrl("example.com:8080/path")).isEqualTo("https://example.com:8080/path");
    }

    @Test
    void shouldTrimLongUrl() {
        assertThat(convertLongUrl("  https://example.com  ")).isEqualTo("https://example.com");
    }

    @Test
    void shouldKeepUppercaseHttpScheme() {
        assertThat(convertLongUrl("HTTP://Example.com/a")).isEqualTo("HTTP://Example.com/a");
    }

    @Test
    void shouldNotPrependHttpsWhenColonIsMissingAfterHttps() {
        assertThat(convertLongUrl("https//wedding.hrva.cc")).isEqualTo("https//wedding.hrva.cc");
    }

    @Test
    void shouldNotPrependHttpsWhenColonIsMissingAfterHttp() {
        assertThat(convertLongUrl("http//example.com")).isEqualTo("http//example.com");
    }

    @Test
    void shouldNotPrependHttpsToFtpScheme() {
        assertThat(convertLongUrl("ftp://x.com")).isEqualTo("ftp://x.com");
    }

    @Test
    void shouldNotPrependHttpsToMailtoScheme() {
        assertThat(convertLongUrl("mailto:a@b.c")).isEqualTo("mailto:a@b.c");
    }

    @Test
    void shouldNotPrependHttpsWhenSlashIsMissingAfterHttps() {
        assertThat(convertLongUrl("https:/x.com")).isEqualTo("https:/x.com");
    }

    @Test
    void shouldNotPrependHttpsWhenSlashesAreMissingAfterHttps() {
        assertThat(convertLongUrl("https:x.com")).isEqualTo("https:x.com");
    }

    private String convertLongUrl(final String longUrl) {
        return converter.convert(CreateUrlDto.builder().longUrl(longUrl).build()).getLongUrl();
    }
}
