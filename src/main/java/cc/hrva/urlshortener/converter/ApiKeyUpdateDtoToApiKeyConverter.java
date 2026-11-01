package cc.hrva.urlshortener.converter;

import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import cc.hrva.urlshortener.dto.ApiKeyUpdateDto;
import cc.hrva.urlshortener.model.ApiKey;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiKeyUpdateDtoToApiKeyConverter implements Converter<ApiKeyUpdateDto, ApiKey> {

    private final ApiKeyRepository apiKeyRepository;
    private final Clock clock;

    @Override
    public ApiKey convert(final ApiKeyUpdateDto apiKeyUpdateDto) {
        final var existingApiKey = apiKeyRepository.findById(apiKeyUpdateDto.getId())
                .orElseThrow(() -> new ApiKeyNotFoundException(String.format("API key with ID %d has not been found",
                        apiKeyUpdateDto.getId())));
        final var editedApiKey = ApiKey.builder()
                .id(existingApiKey.getId())
                .apiCallsUsed(existingApiKey.getApiCallsUsed())
                .expirationDate(existingApiKey.getExpirationDate())
                .active(existingApiKey.isActive())
                .build();

        if (Boolean.TRUE.equals(apiKeyUpdateDto.getNeverExpires())) {
            editedApiKey.setExpirationDate(null);
        } else if (apiKeyUpdateDto.getExpiresInDays() != null) {
            editedApiKey.setExpirationDate(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())).truncatedTo(ChronoUnit.SECONDS).plusDays(apiKeyUpdateDto.getExpiresInDays()));
        } else if (apiKeyUpdateDto.getExpirationDate() != null) {
            editedApiKey.setExpirationDate(apiKeyUpdateDto.getExpirationDate());
        }
        if (apiKeyUpdateDto.getActive() != null) {
            editedApiKey.setActive(apiKeyUpdateDto.getActive());
        }
        return editedApiKey;
    }

}
