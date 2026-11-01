package cc.hrva.urlshortener.converter;

import java.time.LocalDateTime;
import java.util.Optional;
import cc.hrva.urlshortener.dto.ApiKeyUpdateDto;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyUpdateDtoToApiKeyConverterTest {

    private static final java.time.Clock CLOCK = java.time.Clock.fixed(java.time.Instant.parse("2026-10-30T23:50:07.987654321Z"), java.time.ZoneOffset.UTC);
    private static final LocalDateTime EXPIRY = LocalDateTime.of(2027, 12, 1, 0, 0);

    private ApiKeyUpdateDtoToApiKeyConverter converter;

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @BeforeEach
    void setUp() {
        this.converter = new ApiKeyUpdateDtoToApiKeyConverter(this.apiKeyRepository, CLOCK);
    }

    @Test
    void shouldConvert() {
        final ApiKeyUpdateDto apiKeyUpdateDto = ApiKeyUpdateDto.builder().id(1L).active(true).build();
        final ApiKey apiKey = ApiKey.builder().id(1L).active(true).build();

        when(apiKeyRepository.findById(anyLong())).thenReturn(Optional.ofNullable(apiKey));

        final var edited = converter.convert(apiKeyUpdateDto);
        assertThat(edited.getId()).isEqualTo(1L);
        assertThat(edited.isActive()).isTrue();
    }

    @Test
    void theLoadedKeyIsLeftUntouched() {
        final var apiKey = loaded();

        final var edited = converter.convert(ApiKeyUpdateDto.builder().id(1L).expirationDate(EXPIRY.plusMonths(6)).active(false).build());

        assertThat(edited).isNotSameAs(apiKey);
        assertThat(edited.getKeyHash()).isNull();
        assertThat(apiKey.getApiCallsUsed()).isEqualTo(42L);
        assertThat(apiKey.getExpirationDate()).isEqualTo(EXPIRY);
        assertThat(apiKey.isActive()).isTrue();
    }

    @Test
    void theUsageIsNeverEdited() {
        loaded();

        final var result = converter.convert(ApiKeyUpdateDto.builder().id(1L).active(false).build());

        assertThat(result.getApiCallsUsed()).isEqualTo(42L);
        assertThat(result.getExpirationDate()).isEqualTo(EXPIRY);
        assertThat(result.isActive()).isFalse();
    }

    @Test
    void anExpiryInDaysCountsFromNow() {
        loaded();

        final var result = converter.convert(ApiKeyUpdateDto.builder().id(1L).expiresInDays(30).build());

        assertThat(result.getExpirationDate()).isEqualTo(LocalDateTime.of(2026, 11, 29, 23, 50, 7));
        assertThat(result.isActive()).isTrue();
    }

    @Test
    void noExpiryClearsTheDate() {
        loaded();

        assertThat(converter.convert(ApiKeyUpdateDto.builder().id(1L).neverExpires(true).build()).getExpirationDate()).isNull();
    }

    @Test
    void neverExpiresFalseAloneKeepsTheDate() {
        loaded();

        assertThat(converter.convert(ApiKeyUpdateDto.builder().id(1L).neverExpires(false).build()).getExpirationDate()).isEqualTo(EXPIRY);
    }

    @Test
    void anExpirationDateStillSetsTheDate() {
        loaded();

        assertThat(converter.convert(ApiKeyUpdateDto.builder().id(1L).expirationDate(EXPIRY.plusYears(1)).build()).getExpirationDate())
                .isEqualTo(EXPIRY.plusYears(1));
    }

    @Test
    void shouldFailConvert() {
        final ApiKeyUpdateDto apiKeyUpdateDto = ApiKeyUpdateDto.builder().id(1L).build();

        when(apiKeyRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatCode(() -> converter.convert(apiKeyUpdateDto))
                .isInstanceOf(ApiKeyNotFoundException.class)
                .hasMessage(String.format("API key with ID %d has not been found", apiKeyUpdateDto.getId()));
    }

    private ApiKey loaded() {
        final var apiKey = ApiKey.builder().id(1L).keyHash("stored-hash").apiCallsUsed(42L).expirationDate(EXPIRY).active(true).build();
        when(apiKeyRepository.findById(1L)).thenReturn(Optional.of(apiKey));
        return apiKey;
    }
}
