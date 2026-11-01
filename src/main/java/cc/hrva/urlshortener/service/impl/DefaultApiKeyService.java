package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.ApiKeyUpdateDtoToApiKeyConverter;
import cc.hrva.urlshortener.exception.ApiKeyActiveException;
import cc.hrva.urlshortener.exception.ApiKeyInactiveException;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.exception.InvalidApiKeyException;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.specification.ApiKeySpecification;
import cc.hrva.urlshortener.util.EmailAddresses;
import cc.hrva.urlshortener.util.SortWhitelist;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.dto.ApiKeyCreateDto;
import cc.hrva.urlshortener.dto.ApiKeyResponse;
import cc.hrva.urlshortener.dto.ApiKeyUpdateDto;
import cc.hrva.urlshortener.dto.CreatedApiKeyResponse;
import cc.hrva.urlshortener.security.ApiKeyTokens;
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
    private static final String INACTIVE_KEY_IS_FINAL = "A revoked or expired key can't be turned back on or changed. Delete it and make a new key.";
    private static final String CREATE_DATE = "createDate";
    private static final String ID = "id";
    private static final String LAST_USED_AT = "lastUsedAt";
    private static final String EXPIRATION_DATE = "expirationDate";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc(CREATE_DATE), Sort.Order.desc(ID));
    private static final SortWhitelist API_KEY_SORTS = SortWhitelist.of(CREATE_DATE, "apiCallsUsed", LAST_USED_AT, EXPIRATION_DATE)
            .byDefault(Sort.by(Sort.Order.desc(CREATE_DATE)))
            .thenBy(Sort.Order.desc(ID))
            .nullsLast(LAST_USED_AT, EXPIRATION_DATE);
    private static final SortWhitelist MY_INACTIVE_KEY_SORTS = SortWhitelist.of(CREATE_DATE)
            .byDefault(Sort.by(Sort.Order.desc(CREATE_DATE)))
            .thenBy(Sort.Order.desc(ID));

    private final UserService userService;
    private final AppProperties appProperties;
    private final ApiKeyValidator apiKeyValidator;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyUpdateDtoToApiKeyConverter apiKeyConverter;
    private final Clock clock;

    @Override
    @Transactional
    public CreatedApiKeyResponse generateNewApiKey(final ApiKeyCreateDto expiry) {
        final var user = userService.getUserFromToken();
        final var expiresInDays = expiry != null ? expiry.expiresInDays() : null;
        final var neverExpires = expiry != null ? expiry.neverExpires() : null;
        apiKeyValidator.validateExpiry(expiresInDays, neverExpires, null);
        apiKeyValidator.apiKeySlotsAvailable(user);

        final var token = ApiKeyTokens.generate();
        final var apiKey = apiKeyRepository.save(new ApiKey(user, expirationOf(expiresInDays, neverExpires), token));
        log.info("Generated new API key for user email={} keyPrefix={} neverExpires={}", EmailAddresses.masked(user.getEmail()), apiKey.getKeyPrefix(),
                apiKey.getExpirationDate() == null);
        return CreatedApiKeyResponse.from(apiKey, token.key());
    }

    private LocalDateTime expirationOf(final Integer expiresInDays, final Boolean neverExpires) {
        if (Boolean.TRUE.equals(neverExpires)) {
            return null;
        }
        if (expiresInDays != null) {
            return now().plusDays(expiresInDays);
        }
        return now().plusMonths(appProperties.getApiKeyExpirationInMonths());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneId.systemDefault())).truncatedTo(ChronoUnit.SECONDS);
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
    public void requireActive(final Long apiKeyId) {
        if (!apiKeyRepository.existsByIdAndActiveTrue(apiKeyId)) {
            throw new InvalidApiKeyException("API key is invalid");
        }
    }

    @Override
    public ApiKey fetchApiKeyByKey(final String key) {
        return findByKey(key)
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
        final var apiKey = apiKeyRepository.findForUpdateById(apiKeyUpdateDto.getId())
                .orElseThrow(() -> new ApiKeyNotFoundException("Api key doesn't exist"));
        apiKeyValidator.verifyUserAdminOrOwner(apiKey);
        if (!apiKey.isActive()) {
            log.info("Edit of an inactive API key refused id={}", apiKey.getId());
            throw new ApiKeyInactiveException(INACTIVE_KEY_IS_FINAL);
        }
        apiKeyValidator.validateExpiry(apiKeyUpdateDto.getExpiresInDays(), apiKeyUpdateDto.getNeverExpires(), apiKeyUpdateDto.getExpirationDate());
        final var editedApiKey = Objects.requireNonNull(apiKeyConverter.convert(apiKeyUpdateDto));

        apiKey.setExpirationDate(editedApiKey.getExpirationDate());
        apiKey.setActive(editedApiKey.isActive());
        return ApiKeyResponse.from(apiKeyRepository.save(apiKey));
    }

    @Override
    @Transactional
    public void deactivateExpired() {
        final var apiKeys = apiKeyRepository.findByExpirationDateIsLessThanEqualAndActiveTrue(LocalDateTime.now(clock.withZone(ZoneId.systemDefault()))).stream()
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
        return findByKey(key)
            .orElseThrow(() -> new ApiKeyNotFoundException("API key doesn't exist"));
    }

    private Optional<ApiKey> findByKey(final String key) {
        final var hash = ApiKeyTokens.hash(key);
        return apiKeyRepository.findApiKeyByKeyHash(hash).filter(apiKey -> ApiKeyTokens.sameHash(apiKey.getKeyHash(), hash));
    }

}
