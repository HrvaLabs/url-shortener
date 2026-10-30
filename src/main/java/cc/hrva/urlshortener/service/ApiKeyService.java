package cc.hrva.urlshortener.service;

import java.util.List;
import cc.hrva.urlshortener.dto.ApiKeyResponse;
import cc.hrva.urlshortener.dto.ApiKeyUpdateDto;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ApiKeyService {

    void deactivateExpired();
    ApiKeyResponse generateNewApiKey();
    ApiKeyResponse revokeApiKey(Long id);
    ApiKeyResponse activateApiKey(Long id);
    void deleteApiKey(Long id);
    int deleteMyInactiveApiKeys();
    List<ApiKeyResponse> fetchMyApiKeys(Boolean active);
    Page<ApiKeyResponse> fetchMyInactiveApiKeys(Pageable pageable);
    Page<ApiKeyResponse> fetchAllApiKeys(String search, Boolean active, Pageable pageable);
    ApiKey findApiKeyByKey(String key);
    ApiKey fetchApiKeyByKey(String key);
    void recordUse(Long apiKeyId);
    int getActiveApiKeysCountForUser(User user);
    ApiKeyResponse updateKey(ApiKeyUpdateDto apiKeyUpdateDto);

}
