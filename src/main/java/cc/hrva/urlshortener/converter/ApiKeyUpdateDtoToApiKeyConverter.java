package cc.hrva.urlshortener.converter;

import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import cc.hrva.urlshortener.dto.ApiKeyUpdateDto;
import cc.hrva.urlshortener.model.ApiKey;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiKeyUpdateDtoToApiKeyConverter implements Converter<ApiKeyUpdateDto, ApiKey> {

    private final ApiKeyRepository apiKeyRepository;

    @Override
    public ApiKey convert(final ApiKeyUpdateDto apiKeyUpdateDto) {
        final var existingApiKey = apiKeyRepository.findById(apiKeyUpdateDto.getId())
                .orElseThrow(() -> new ApiKeyNotFoundException(String.format("API key with ID %d has not been found",
                        apiKeyUpdateDto.getId())));
        final var editedApiKey = ApiKey.builder()
                .id(existingApiKey.getId())
                .apiCallsLimit(existingApiKey.getApiCallsLimit())
                .apiCallsUsed(existingApiKey.getApiCallsUsed())
                .expirationDate(existingApiKey.getExpirationDate())
                .active(existingApiKey.isActive())
                .build();

        if (apiKeyUpdateDto.getApiCallsLimit() != null) {
            editedApiKey.setApiCallsLimit(apiKeyUpdateDto.getApiCallsLimit() > 0 ? apiKeyUpdateDto.getApiCallsLimit() : null);
        }
        if (apiKeyUpdateDto.getApiCallsUsed() != null) {
            editedApiKey.setApiCallsUsed(apiKeyUpdateDto.getApiCallsUsed());
        }
        if (apiKeyUpdateDto.getExpirationDate() != null) {
            editedApiKey.setExpirationDate(apiKeyUpdateDto.getExpirationDate());
        }
        if (apiKeyUpdateDto.getActive() != null) {
            editedApiKey.setActive(apiKeyUpdateDto.getActive());
        }
        return editedApiKey;
    }

}
