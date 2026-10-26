package cc.hrva.urlshortener.converter;

import java.util.Optional;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlUpdateDtoToUrlConverterTest {

    private UrlUpdateDtoToUrlConverter converter;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        this.converter = new UrlUpdateDtoToUrlConverter(this.urlRepository, this.passwordEncoder);
    }

    @Test
    void shouldUpdateVisitLimitAndExpirationDate() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com").build();
        final var updateDto = UrlUpdateDto.builder().id(1L).visitLimit(100L).expirationDate(java.time.LocalDateTime.now().plusDays(1)).build();

        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));

        final var result = converter.convert(updateDto);

        assertThat(result.getVisitLimit()).isEqualTo(100L);
        assertThat(result.getExpirationDate()).isEqualTo(updateDto.getExpirationDate());
    }

    @Test
    void shouldClearVisitLimitWhenNullOrZero() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com").visitLimit(50L).build();
        final var updateDto = UrlUpdateDto.builder().id(1L).visitLimit(null).build();

        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));

        final var result = converter.convert(updateDto);

        assertThat(result.getVisitLimit()).isNull();
    }

    @Test
    void shouldClearVisitLimitWhenNegativeOrZero() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com").visitLimit(50L).build();
        final var updateDto = UrlUpdateDto.builder().id(1L).visitLimit(0L).build();

        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));

        final var result = converter.convert(updateDto);

        assertThat(result.getVisitLimit()).isNull();
    }

    @Test
    void shouldClearExpirationDateWhenNull() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com").expirationDate(java.time.LocalDateTime.now()).build();
        final var updateDto = UrlUpdateDto.builder().id(1L).expirationDate(null).build();

        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));

        final var result = converter.convert(updateDto);

        assertThat(result.getExpirationDate()).isNull();
    }

    @Test
    void shouldReplaceTagsWhenProvided() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com")
                .tags(new java.util.LinkedHashSet<>(java.util.List.of("old"))).build();
        final var updateDto = UrlUpdateDto.builder().id(1L)
                .tags(new java.util.LinkedHashSet<>(java.util.List.of("New", "fresh"))).build();

        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));

        final var result = converter.convert(updateDto);

        assertThat(result.getTags()).containsExactlyInAnyOrder("new", "fresh");
    }

    @Test
    void shouldLeaveTagsUnchangedWhenNull() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com")
                .tags(new java.util.LinkedHashSet<>(java.util.List.of("keep"))).build();
        final var updateDto = UrlUpdateDto.builder().id(1L).build();

        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));

        final var result = converter.convert(updateDto);

        assertThat(result.getTags()).containsExactly("keep");
    }

    @Test
    void shouldSetPasswordWhenProvided() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com").build();
        final var updateDto = UrlUpdateDto.builder().id(1L).password("secret").build();
        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));
        when(passwordEncoder.encode("secret")).thenReturn("hashed");

        final var result = converter.convert(updateDto);

        assertThat(result.getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    void shouldClearPasswordWhenEmpty() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com").passwordHash("old").build();
        final var updateDto = UrlUpdateDto.builder().id(1L).password("").build();
        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));

        final var result = converter.convert(updateDto);

        assertThat(result.getPasswordHash()).isNull();
    }

    @Test
    void shouldLeavePasswordWhenNull() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com").passwordHash("keep").build();
        final var updateDto = UrlUpdateDto.builder().id(1L).build();
        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));

        final var result = converter.convert(updateDto);

        assertThat(result.getPasswordHash()).isEqualTo("keep");
    }

    @Test
    void shouldLeaveTheLoadedLinkUnchanged() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com").visitLimit(50L)
                .tags(new java.util.LinkedHashSet<>(java.util.List.of("keep"))).passwordHash("old").build();
        final var updateDto = UrlUpdateDto.builder().id(1L).longUrl("https://example.org/new").visitLimit(100L)
                .expirationDate(java.time.LocalDateTime.now().plusDays(1))
                .tags(new java.util.LinkedHashSet<>(java.util.List.of("New"))).password("secret").build();
        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));
        when(passwordEncoder.encode("secret")).thenReturn("hashed");

        final var result = converter.convert(updateDto);

        assertThat(result).isNotSameAs(existingUrl)
                .extracting(Url::getId, Url::getLongUrl, Url::getVisitLimit, Url::getTags, Url::getPasswordHash)
                .containsExactly(1L, "https://example.org/new", 100L, java.util.Set.of("new"), "hashed");
        assertThat(existingUrl)
                .extracting(Url::getLongUrl, Url::getVisitLimit, Url::getExpirationDate, Url::getTags, Url::getPasswordHash)
                .containsExactly("https://example.com", 50L, null, java.util.Set.of("keep"), "old");
    }

    @Test
    void shouldGiveTheEditedLinkItsOwnTags() {
        final var existingUrl = Url.builder().id(1L).longUrl("https://example.com")
                .tags(new java.util.LinkedHashSet<>(java.util.List.of("keep"))).build();
        final var updateDto = UrlUpdateDto.builder().id(1L).build();
        when(urlRepository.findById(anyLong())).thenReturn(Optional.of(existingUrl));

        final var result = converter.convert(updateDto);
        result.getTags().add("added");

        assertThat(existingUrl.getTags()).containsExactly("keep");
    }

    @Test
    void shouldFailWhenUrlNotFound() {
        final var updateDto = UrlUpdateDto.builder().id(1L).build();

        when(urlRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> converter.convert(updateDto))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessage("Url with id 1 doesn't exist");
    }
}
