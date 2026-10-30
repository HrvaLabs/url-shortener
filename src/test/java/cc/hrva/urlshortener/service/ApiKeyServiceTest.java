package cc.hrva.urlshortener.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.ApiKeyUpdateDtoToApiKeyConverter;
import cc.hrva.urlshortener.dto.ApiKeyResponse;
import cc.hrva.urlshortener.dto.ApiKeyUpdateDto;
import cc.hrva.urlshortener.exception.ApiKeyActiveException;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.exception.InvalidApiKeyException;
import cc.hrva.urlshortener.exception.InvalidSortException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.service.impl.DefaultApiKeyService;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    private ApiKeyService apiKeyService;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id"));

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private UserService userService;

    @Mock
    private AppProperties appProperties;

    @Mock
    private ApiKeyValidator apiKeyValidator;

    @Mock
    private ApiKeyUpdateDtoToApiKeyConverter apiKeyConverter;

    @BeforeEach
    void setUp() {
        this.apiKeyService = new DefaultApiKeyService(
                userService,
                appProperties,
                apiKeyValidator,
                apiKeyRepository,
                apiKeyConverter);
    }

    @Test
    void shouldGenerateNewApiKey() {
        final User user = User.builder().build();
        final ApiKey apiKey = ApiKey.builder().build();

        when(userService.getUserFromToken()).thenReturn(user);
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(apiKey);

        final var result = apiKeyService.generateNewApiKey();
        assertThat(result).isInstanceOf(ApiKeyResponse.class);
    }

    @Test
    void myKeysComeNewestFirstWithTheIdBreakingTies() {
        final var user = User.builder().id(5L).build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(apiKeyRepository.findByOwner(user, NEWEST_FIRST)).thenReturn(List.of(ApiKey.builder().id(9L).build(), ApiKey.builder().id(4L).build()));

        final var result = apiKeyService.fetchMyApiKeys(null);

        assertThat(result).extracting(ApiKeyResponse::id).containsExactly(9L, 4L);
    }

    @Test
    void myKeysCanBeOnlyTheActiveOrOnlyTheInactiveOnes() {
        final var user = User.builder().id(5L).build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(apiKeyRepository.findByOwnerAndActive(user, true, NEWEST_FIRST)).thenReturn(List.of(ApiKey.builder().id(9L).active(true).build()));

        assertThat(apiKeyService.fetchMyApiKeys(true)).extracting(ApiKeyResponse::id).containsExactly(9L);
        verify(apiKeyRepository, never()).findByOwner(any(User.class), any(Sort.class));
    }

    @Test
    void myInactiveKeysComeAPageAtATimeNewestFirst() {
        final var user = User.builder().id(5L).build();
        final var secondPage = PageRequest.of(1, 10, NEWEST_FIRST);
        when(userService.getUserFromToken()).thenReturn(user);
        when(apiKeyRepository.findByOwnerAndActiveFalse(user, secondPage))
                .thenReturn(new PageImpl<>(List.of(ApiKey.builder().id(3L).build()), secondPage, 11));

        final var result = apiKeyService.fetchMyInactiveApiKeys(PageRequest.of(1, 10));

        assertThat(result.getTotalElements()).isEqualTo(11);
        assertThat(result.getContent()).extracting(ApiKeyResponse::id).containsExactly(3L);
    }

    @Test
    void myInactiveKeysRefuseToSortByAFieldOffTheList() {
        assertThatThrownBy(() -> apiKeyService.fetchMyInactiveApiKeys(PageRequest.of(0, 10, Sort.by("key"))))
                .isInstanceOf(InvalidSortException.class)
                .hasMessage("Sort must be createDate");
        verify(apiKeyRepository, never()).findByOwnerAndActiveFalse(any(), any());
    }

    @Test
    void shouldFailRevokeApiKey() {
        final Long id = 1L;

        when(apiKeyRepository.findById(id)).thenReturn(Optional.empty());

        assertThatCode(() -> apiKeyService.revokeApiKey(id))
                .isInstanceOf(ApiKeyNotFoundException.class)
                .hasMessage("Api key doesn't exist");
    }

    @Test
    void shouldRevokeApiKey() {
        final Long id = 1L;
        final ApiKey apiKey = ApiKey.builder().active(false).build();

        when(apiKeyRepository.findById(id)).thenReturn(Optional.ofNullable(apiKey));
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(apiKey);

        final var result = apiKeyService.revokeApiKey(id);
        assertThat(result.active()).isFalse();
    }

    @Test
    void anInactiveKeyIsDeleted() {
        final var apiKey = ApiKey.builder().id(4L).active(false).build();

        when(apiKeyRepository.findById(4L)).thenReturn(Optional.of(apiKey));
        when(apiKeyRepository.deleteInactiveById(4L)).thenReturn(1);

        apiKeyService.deleteApiKey(4L);

        verify(apiKeyValidator).verifyUserAdminOrOwner(apiKey);
        verify(apiKeyRepository).deleteInactiveById(4L);
    }

    @Test
    void anActiveKeyIsNotDeleted() {
        final var apiKey = ApiKey.builder().id(4L).active(true).build();

        when(apiKeyRepository.findById(4L)).thenReturn(Optional.of(apiKey));

        assertThatCode(() -> apiKeyService.deleteApiKey(4L))
                .isInstanceOf(ApiKeyActiveException.class)
                .hasMessage("Revoke this key before deleting it.");
        verify(apiKeyRepository, never()).deleteInactiveById(anyLong());
    }

    @Test
    void aKeyTurnedOnAgainBeforeTheDeleteIsNotDeleted() {
        final var apiKey = ApiKey.builder().id(4L).active(false).build();

        when(apiKeyRepository.findById(4L)).thenReturn(Optional.of(apiKey));
        when(apiKeyRepository.deleteInactiveById(4L)).thenReturn(0);
        when(apiKeyRepository.existsById(4L)).thenReturn(true);

        assertThatCode(() -> apiKeyService.deleteApiKey(4L))
                .isInstanceOf(ApiKeyActiveException.class)
                .hasMessage("Revoke this key before deleting it.");
    }

    @Test
    void aKeyDeletedByAnotherRequestBeforeTheDeleteIsNotFound() {
        final var apiKey = ApiKey.builder().id(4L).active(false).build();

        when(apiKeyRepository.findById(4L)).thenReturn(Optional.of(apiKey));
        when(apiKeyRepository.deleteInactiveById(4L)).thenReturn(0);
        when(apiKeyRepository.existsById(4L)).thenReturn(false);

        assertThatCode(() -> apiKeyService.deleteApiKey(4L))
                .isInstanceOf(ApiKeyNotFoundException.class)
                .hasMessage("Api key doesn't exist");
    }

    @Test
    void aMissingKeyIsNotFound() {
        when(apiKeyRepository.findById(4L)).thenReturn(Optional.empty());

        assertThatCode(() -> apiKeyService.deleteApiKey(4L))
                .isInstanceOf(ApiKeyNotFoundException.class)
                .hasMessage("Api key doesn't exist");
    }

    @Test
    void someoneElsesKeyIsRefusedBeforeItsStateIsChecked() {
        final var apiKey = ApiKey.builder().id(4L).active(true).build();

        when(apiKeyRepository.findById(4L)).thenReturn(Optional.of(apiKey));
        doThrow(new NoAuthorizationException("You don't have authorization for this action"))
                .when(apiKeyValidator).verifyUserAdminOrOwner(apiKey);

        assertThatCode(() -> apiKeyService.deleteApiKey(4L))
                .isInstanceOf(NoAuthorizationException.class);
        verify(apiKeyRepository, never()).deleteInactiveById(anyLong());
    }

    @Test
    void theCallersInactiveKeysAreDeletedByTheirOwnerId() {
        when(userService.getUserFromToken()).thenReturn(User.builder().id(9L).build());
        when(apiKeyRepository.deleteInactiveByOwnerId(9L)).thenReturn(3);

        assertThat(apiKeyService.deleteMyInactiveApiKeys()).isEqualTo(3);
    }

    @Test
    void aUseIsRecordedWithOneGuardedUpdateAndNeverASaveOfTheLoadedKey() {
        when(apiKeyRepository.recordUse(eq(5L), any(LocalDateTime.class))).thenReturn(1);

        apiKeyService.recordUse(5L);

        verify(apiKeyRepository).recordUse(eq(5L), any(LocalDateTime.class));
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void aUseOfAKeyThatIsNoLongerActiveIsRefusedAsAnInvalidKey() {
        when(apiKeyRepository.recordUse(eq(5L), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> apiKeyService.recordUse(5L))
                .isInstanceOf(InvalidApiKeyException.class)
                .hasMessage("API key is invalid");
    }

    @Test
    void shouldFetchApiKeyByKey() {
        final ApiKey apiKey = ApiKey.builder().apiCallsUsed(1L).build();
        final String key = "key";
        when(apiKeyRepository.findApiKeyByKey(key)).thenReturn(Optional.ofNullable(apiKey));

        assertThat(apiKeyService.fetchApiKeyByKey(key)).usingRecursiveComparison().isEqualTo(apiKey);
    }

    @Test
    void shouldFailFetchApiKeyByKey() {
        final String key = "key";

        when(apiKeyRepository.findApiKeyByKey(key)).thenReturn(Optional.empty());

        assertThatCode(() -> apiKeyService.fetchApiKeyByKey(key))
                .isInstanceOf(ApiKeyNotFoundException.class)
                .hasMessage("Sent API key doesn't exist");
    }

    @Test
    void allKeysWithoutASortComeNewestFirstWithTheIdBreakingTies() {
        final var apiKey = ApiKey.builder().build();
        when(apiKeyRepository.findAll(anySpecification(), eq(PageRequest.of(0, 20, NEWEST_FIRST))))
                .thenReturn(new PageImpl<>(Collections.singletonList(apiKey)));

        final var result = apiKeyService.fetchAllApiKeys(null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void allKeysSortByCallsUsedOrExpiryWithTheIdBreakingTies() {
        when(apiKeyRepository.findAll(anySpecification(), any(Pageable.class))).thenReturn(Page.empty());

        apiKeyService.fetchAllApiKeys("ana", false, PageRequest.of(0, 20, Sort.by(Sort.Order.desc("apiCallsUsed"))));
        apiKeyService.fetchAllApiKeys(null, true, PageRequest.of(0, 20, Sort.by(Sort.Order.asc("expirationDate"))));

        verify(apiKeyRepository).findAll(anySpecification(), eq(PageRequest.of(0, 20, Sort.by(Sort.Order.desc("apiCallsUsed"), Sort.Order.desc("id")))));
        verify(apiKeyRepository).findAll(anySpecification(), eq(PageRequest.of(0, 20, Sort.by(Sort.Order.asc("expirationDate"), Sort.Order.desc("id")))));
    }

    @Test
    void allKeysRefuseToSortByAFieldOffTheList() {
        assertThatThrownBy(() -> apiKeyService.fetchAllApiKeys(null, null, PageRequest.of(0, 20, Sort.by("key"))))
                .isInstanceOf(InvalidSortException.class)
                .hasMessage("Sort must be createDate, apiCallsUsed or expirationDate");
        verify(apiKeyRepository, never()).findAll(anySpecification(), any(Pageable.class));
    }

    @SuppressWarnings("unchecked")
    private static Specification<ApiKey> anySpecification() {
        return any(Specification.class);
    }

    @Test
    void shouldUpdateKey() {
        final ApiKey apiKey = ApiKey.builder().id(1L).build();
        final ApiKeyUpdateDto apiKeyUpdateDto = ApiKeyUpdateDto.builder().id(1L).build();

        when(apiKeyConverter.convert(apiKeyUpdateDto)).thenReturn(ApiKey.builder().id(1L).build());
        when(apiKeyRepository.findById(1L)).thenReturn(Optional.of(apiKey));
        when(apiKeyRepository.save(apiKey)).thenReturn(apiKey);

        final var result = apiKeyService.updateKey(apiKeyUpdateDto);
        assertThat(result).isInstanceOf(ApiKeyResponse.class);
    }

    @Test
    void anEditIsAppliedToTheKeyLoadedInTheWriteTransactionAndThatKeyIsSaved() {
        final var expiry = LocalDateTime.of(2027, 6, 1, 0, 0);
        final var loaded = ApiKey.builder().id(1L).key("secret-key").apiCallsLimit(100L).apiCallsUsed(42L).active(true).build();
        final var edited = ApiKey.builder().id(1L).apiCallsLimit(500L).apiCallsUsed(0L).expirationDate(expiry).active(false).build();
        final var apiKeyUpdateDto = ApiKeyUpdateDto.builder().id(1L).build();

        when(apiKeyConverter.convert(apiKeyUpdateDto)).thenReturn(edited);
        when(apiKeyRepository.findById(1L)).thenReturn(Optional.of(loaded));
        when(apiKeyRepository.save(loaded)).thenReturn(loaded);

        apiKeyService.updateKey(apiKeyUpdateDto);

        verify(apiKeyRepository).save(loaded);
        verify(apiKeyRepository, never()).save(edited);
        assertThat(loaded.getKey()).isEqualTo("secret-key");
        assertThat(loaded.getApiCallsLimit()).isEqualTo(500L);
        assertThat(loaded.getApiCallsUsed()).isZero();
        assertThat(loaded.getExpirationDate()).isEqualTo(expiry);
        assertThat(loaded.isActive()).isFalse();
    }

}