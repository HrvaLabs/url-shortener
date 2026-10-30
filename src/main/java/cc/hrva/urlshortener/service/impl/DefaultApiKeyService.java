package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.ApiKeyUpdateDtoToApiKeyConverter;
import cc.hrva.urlshortener.exception.ApiKeyActiveException;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.exception.InvalidApiKeyException;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.specification.ApiKeySpecification;
import cc.hrva.urlshortener.util.SortWhitelist;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.dto.ApiKeyResponse;
import cc.hrva.urlshortener.dto.ApiKeyUpdateDto;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.ApiKeyService;
import cc.hrva.urlshortener.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultApiKeyService implements ApiKeyService {

    private static final String REVOKE_BEFORE_DELETING = "Revoke this key before deleting it.";
    private static final String CREATE_DATE = "createDate";
    private static final String ID = "id";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc(CREATE_DATE), Sort.Order.desc(ID));
    private static final SortWhitelist API_KEY_SORTS = SortWhitelist.of(CREATE_DATE, "apiCallsUsed", "expirationDate")
            .byDefault(Sort.by(Sort.Order.desc(CREATE_DATE)))
            .thenBy(Sort.Order.desc(ID));
    private static final SortWhitelist MY_INACTIVE_KEY_SORTS = SortWhitelist.of(CREATE_DATE)
            .byDefault(Sort.by(Sort.Order.desc(CREATE_DATE)))
            .thenBy(Sort.Order.desc(ID));

    private final UserService userService;
    private final AppProperties appProperties;
    private final ApiKeyValidator apiKeyValidator;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyUpdateDtoToApiKeyConverter apiKeyConverter;

    @Override
    @Transactional
    public ApiKeyResponse generateNewApiKey() {
        final var user = userService.getUserFromToken();
        log.info("Generated new API key for user email={}", user.getEmail());

        apiKeyValidator.apiKeySlotsAvailable(user);

        return ApiKeyResponse.from(apiKeyRepository.save(new ApiKey(user, appProperties)));
    }

    @Override
    public List<ApiKeyResponse> fetchMyApiKeys(final Boolean active) {
        final var user = userService.getUserFromToken();
        final var keys = active == null ? apiKeyRepository.findByOwner(user, NEWEST_FIRST) : apiKeyRepository.findByOwnerAndActive(user, active, NEWEST_FIRST);
        return keys.stream().map(ApiKeyResponse::from).toList();
    }

    @Override
    public Page<ApiKeyResponse> fetchMyInactiveApiKeys(final Pageable pageable) {
        final var sorted = MY_INACTIVE_KEY_SORTS.apply(pageable);
        return apiKeyRepository.findByOwnerAndActiveFalse(userService.getUserFromToken(), sorted).map(ApiKeyResponse::from);
    }

    @Override
    @Transactional
    public ApiKeyResponse revokeApiKey(final Long id) {
        log.info("Revoked API key id={}", id);

        final var apiKey = apiKeyRepository.findById(id)
                .orElseThrow(() -> new ApiKeyNotFoundException("Api key doesn't exist"));

        apiKeyValidator.verifyUserAdminOrOwner(apiKey);
        apiKey.setActive(false);
        return ApiKeyResponse.from(apiKeyRepository.save(apiKey));
    }

    @Override
    @Transactional
    public ApiKeyResponse activateApiKey(final Long id) {
        log.info("Activated API key id={}", id);

        final var apiKey = apiKeyRepository.findById(id)
                .orElseThrow(() -> new ApiKeyNotFoundException("Api key doesn't exist"));

        apiKeyValidator.verifyUserAdminOrOwner(apiKey);
        apiKey.setActive(true);
        return ApiKeyResponse.from(apiKeyRepository.save(apiKey));
    }

    @Override
    @Transactional
    public void deleteApiKey(final Long id) {
        log.info("Delete API key id={}", id);

        final var apiKey = apiKeyRepository.findById(id)
                .orElseThrow(() -> new ApiKeyNotFoundException("Api key doesn't exist"));

        apiKeyValidator.verifyUserAdminOrOwner(apiKey);
        if (apiKey.isActive()) {
            throw new ApiKeyActiveException(REVOKE_BEFORE_DELETING);
        }
        if (apiKeyRepository.deleteInactiveById(id) == 0) {
            if (!apiKeyRepository.existsById(id)) {
                log.info("API key was deleted by a concurrent request id={}", id);
                throw new ApiKeyNotFoundException("Api key doesn't exist");
            }
            throw new ApiKeyActiveException(REVOKE_BEFORE_DELETING);
        }
    }

    @Override
    @Transactional
    public int deleteMyInactiveApiKeys() {
        final var user = userService.getUserFromToken();
        final var deleted = apiKeyRepository.deleteInactiveByOwnerId(user.getId());
        log.info("Deleted {} inactive API keys of user id={}", deleted, user.getId());

        return deleted;
    }

    @Override
    @Transactional
    public void recordUse(final Long apiKeyId) {
        if (apiKeyRepository.recordUse(apiKeyId, LocalDateTime.now()) == 0) {
            throw new InvalidApiKeyException("API key is invalid");
        }
    }

    @Override
    public ApiKey fetchApiKeyByKey(final String key) {
        return apiKeyRepository.findApiKeyByKey(key)
                .orElseThrow(() -> new ApiKeyNotFoundException("Sent API key doesn't exist"));
    }

    @Override
    public Page<ApiKeyResponse> fetchAllApiKeys(final String search, final Boolean active, final Pageable pageable) {
        final var sorted = API_KEY_SORTS.apply(pageable);
        var spec = Specification.<ApiKey>unrestricted();
        if (StringUtils.isNotBlank(search)) {
            spec = spec.and(ApiKeySpecification.searchMatches(search.strip()));
        }
        if (active != null) {
            spec = spec.and(ApiKeySpecification.hasActive(active));
        }
        return apiKeyRepository.findAll(spec, sorted).map(ApiKeyResponse::from);
    }

    @Override
    @Transactional
    public ApiKeyResponse updateKey(final ApiKeyUpdateDto apiKeyUpdateDto) {
        log.info("Updated API key id={}", apiKeyUpdateDto.getId());
        final var editedApiKey = Objects.requireNonNull(apiKeyConverter.convert(apiKeyUpdateDto));
        final var apiKey = apiKeyRepository.findById(editedApiKey.getId())
                .orElseThrow(() -> new ApiKeyNotFoundException("Api key doesn't exist"));

        apiKey.setApiCallsLimit(editedApiKey.getApiCallsLimit());
        apiKey.setApiCallsUsed(editedApiKey.getApiCallsUsed());
        apiKey.setExpirationDate(editedApiKey.getExpirationDate());
        apiKey.setActive(editedApiKey.isActive());
        return ApiKeyResponse.from(apiKeyRepository.save(apiKey));
    }

    @Override
    @Transactional
    public void deactivateExpired() {
        final var apiKeys = apiKeyRepository.findByExpirationDateIsLessThanEqualAndActiveTrue(LocalDateTime.now()).stream()
                .map(ApiKey::deactivate)
                .toList();

        apiKeyRepository.saveAll(apiKeys);
        if (!apiKeys.isEmpty()) log.info("Deactivated {} api keys", apiKeys.size());
    }

    @Override
    public int getActiveApiKeysCountForUser(final User user) {
        return apiKeyRepository.findByOwnerAndActiveTrue(user).size();
    }

    @Override
    public ApiKey findApiKeyByKey(final String key) {
        return apiKeyRepository.findApiKeyByKey(key)
            .orElseThrow(() -> new ApiKeyNotFoundException("API key doesn't exist"));
    }

}
