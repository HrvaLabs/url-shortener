package cc.hrva.urlshortener.converter;

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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyUpdateDtoToApiKeyConverterTest {

    private ApiKeyUpdateDtoToApiKeyConverter converter;

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @BeforeEach
    void setUp() {
        this.converter = new ApiKeyUpdateDtoToApiKeyConverter(this.apiKeyRepository);
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
        final var expiry = java.time.LocalDateTime.of(2026, 12, 1, 0, 0);
        final var apiKey = ApiKey.builder().id(1L).key("secret-key").apiCallsLimit(100L).apiCallsUsed(42L).expirationDate(expiry).active(true).build();
        when(apiKeyRepository.findById(1L)).thenReturn(Optional.of(apiKey));

        final var edited = converter.convert(ApiKeyUpdateDto.builder().id(1L).apiCallsLimit(500L).apiCallsUsed(0L)
                .expirationDate(expiry.plusMonths(6)).active(false).build());

        org.assertj.core.api.Assertions.assertThat(edited).isNotSameAs(apiKey);
        org.assertj.core.api.Assertions.assertThat(edited.getKey()).isNull();
        org.assertj.core.api.Assertions.assertThat(apiKey.getApiCallsLimit()).isEqualTo(100L);
        org.assertj.core.api.Assertions.assertThat(apiKey.getApiCallsUsed()).isEqualTo(42L);
        org.assertj.core.api.Assertions.assertThat(apiKey.getExpirationDate()).isEqualTo(expiry);
        org.assertj.core.api.Assertions.assertThat(apiKey.isActive()).isTrue();
    }

    @Test
    void changesOnlyTheLimitWhenOnlyTheLimitIsSent() {
        final var expiry = java.time.LocalDateTime.now().plusDays(30);
        final var apiKey = ApiKey.builder().id(1L).apiCallsLimit(100L).apiCallsUsed(42L).expirationDate(expiry).active(true).build();
        when(apiKeyRepository.findById(1L)).thenReturn(java.util.Optional.of(apiKey));

        final var result = converter.convert(ApiKeyUpdateDto.builder().id(1L).apiCallsLimit(500L).build());

        org.assertj.core.api.Assertions.assertThat(result.getApiCallsLimit()).isEqualTo(500L);
        org.assertj.core.api.Assertions.assertThat(result.getApiCallsUsed()).isEqualTo(42L);
        org.assertj.core.api.Assertions.assertThat(result.getExpirationDate()).isEqualTo(expiry);
        org.assertj.core.api.Assertions.assertThat(result.isActive()).isTrue();
    }

    @Test
    void aZeroLimitMeansUnlimited() {
        final var apiKey = ApiKey.builder().id(1L).apiCallsLimit(100L).apiCallsUsed(0L).active(true).build();
        when(apiKeyRepository.findById(1L)).thenReturn(java.util.Optional.of(apiKey));

        final var result = converter.convert(ApiKeyUpdateDto.builder().id(1L).apiCallsLimit(0L).build());

        org.assertj.core.api.Assertions.assertThat(result.getApiCallsLimit()).isNull();
    }

    @Test
    void shouldFailConvert() {
        final ApiKeyUpdateDto apiKeyUpdateDto = ApiKeyUpdateDto.builder().id(1L).build();

        when(apiKeyRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatCode(() -> converter.convert(apiKeyUpdateDto))
                .isInstanceOf(ApiKeyNotFoundException.class)
                .hasMessage(String.format("API key with ID %d has not been found", apiKeyUpdateDto.getId()));
    }
}