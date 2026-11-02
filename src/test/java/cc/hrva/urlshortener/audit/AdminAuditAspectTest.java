package cc.hrva.urlshortener.audit;

import cc.hrva.urlshortener.controller.AdminCustomDomainController;
import cc.hrva.urlshortener.controller.AdminEmailLogController;
import cc.hrva.urlshortener.controller.UrlController;
import cc.hrva.urlshortener.controller.UserController;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.UserService;
import java.util.Arrays;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.security.ApiKeyTokens;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AdminAuditAspectTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private UserService userService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private Signature signature;

    private AdminAuditAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new AdminAuditAspect(auditLogService, userService, JsonMapper.builder().build(), new StaticListableBeanFactory().getBeanProvider(ApiKeyRepository.class));
        lenient().when(userService.getUserFromToken()).thenReturn(User.builder().email("admin@hrva.cc").build());
        lenient().when(joinPoint.getSignature()).thenReturn(signature);
    }

    @Test
    void anActionWithNobodySignedInAndNoKnownKeyRunsWithoutARow() throws Throwable {
        when(userService.getUserFromToken()).thenReturn(null);
        onRequest("/api/v1/urls/265", "hrva_unknown");
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("Sent API key doesn't exist"));
        final var audited = UrlController.class.getDeclaredMethod("updateUrl", Long.class, UrlUpdateDto.class).getAnnotation(Audited.class);

        assertThatThrownBy(() -> aspect.logAdminAction(joinPoint, audited)).hasMessage("Sent API key doesn't exist");

        verifyNoInteractions(auditLogService);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void anEditWithAnApiKeyIsRecordedAsTheKeysOwner() throws Throwable {
        final var apiKeyRepository = mock(ApiKeyRepository.class);
        final var beans = new StaticListableBeanFactory();
        beans.addBean("apiKeyRepository", apiKeyRepository);
        final var keyAware = new AdminAuditAspect(auditLogService, userService, JsonMapper.builder().build(), beans.getBeanProvider(ApiKeyRepository.class));
        final var hash = ApiKeyTokens.hash("hrva_audited");
        when(userService.getUserFromToken()).thenReturn(null);
        when(apiKeyRepository.findApiKeyByKeyHash(hash)).thenReturn(Optional.of(ApiKey.builder().keyHash(hash).active(true)
                .owner(User.builder().email("owner@hrva.cc").build()).build()));
        onRequest("/api/v1/urls/265", "hrva_audited");
        final var audited = invoking(UrlController.class, "updateUrl", 265L, UrlUpdateDto.builder().id(265L).visitLimit(10L).build());

        keyAware.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("owner@hrva.cc", AuditAction.UPDATE_URL, "Url", "265", "visitLimit: 10");
    }

    @Test
    void aSignedInUserIsTheActorEvenWhenAKeyIsSent() throws Throwable {
        final var apiKeyRepository = mock(ApiKeyRepository.class);
        final var beans = new StaticListableBeanFactory();
        beans.addBean("apiKeyRepository", apiKeyRepository);
        final var keyAware = new AdminAuditAspect(auditLogService, userService, JsonMapper.builder().build(), beans.getBeanProvider(ApiKeyRepository.class));
        onRequest("PATCH", "/api/v1/urls/265/activate", "hrva_someone_elses");
        final var audited = invoking(UrlController.class, "activateUrl", 265L);

        keyAware.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("admin@hrva.cc", AuditAction.ACTIVATE_URL, "Url", "265", "");
        verifyNoInteractions(apiKeyRepository);
    }

    @Test
    void aKeyIsTheActorOnlyOnTheEditItself() throws Throwable {
        final var apiKeyRepository = mock(ApiKeyRepository.class);
        final var beans = new StaticListableBeanFactory();
        beans.addBean("apiKeyRepository", apiKeyRepository);
        final var keyAware = new AdminAuditAspect(auditLogService, userService, JsonMapper.builder().build(), beans.getBeanProvider(ApiKeyRepository.class));
        when(userService.getUserFromToken()).thenReturn(null);
        onRequest("DELETE", "/api/v1/urls/265", "hrva_audited");
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("Sign in to continue."));
        final var audited = UrlController.class.getDeclaredMethod("deleteUrl", Long.class).getAnnotation(Audited.class);

        assertThatThrownBy(() -> keyAware.logAdminAction(joinPoint, audited)).hasMessage("Sign in to continue.");

        verifyNoInteractions(auditLogService, apiKeyRepository);
    }

    @Test
    void aRevokedKeyNamesNobody() throws Throwable {
        final var apiKeyRepository = mock(ApiKeyRepository.class);
        final var beans = new StaticListableBeanFactory();
        beans.addBean("apiKeyRepository", apiKeyRepository);
        final var keyAware = new AdminAuditAspect(auditLogService, userService, JsonMapper.builder().build(), beans.getBeanProvider(ApiKeyRepository.class));
        final var hash = ApiKeyTokens.hash("hrva_revoked");
        when(userService.getUserFromToken()).thenReturn(null);
        when(apiKeyRepository.findApiKeyByKeyHash(hash)).thenReturn(Optional.of(ApiKey.builder().keyHash(hash).active(false)
                .owner(User.builder().email("owner@hrva.cc").build()).build()));
        onRequest("/api/v1/urls/265", "hrva_revoked");
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("API key is invalid"));
        final var audited = UrlController.class.getDeclaredMethod("updateUrl", Long.class, UrlUpdateDto.class).getAnnotation(Audited.class);

        assertThatThrownBy(() -> keyAware.logAdminAction(joinPoint, audited)).hasMessage("API key is invalid");

        verifyNoInteractions(auditLogService);
    }

    @Test
    void aKeySentOutsideTheLinkApiIsIgnored() throws Throwable {
        final var apiKeyRepository = mock(ApiKeyRepository.class);
        final var beans = new StaticListableBeanFactory();
        beans.addBean("apiKeyRepository", apiKeyRepository);
        final var keyAware = new AdminAuditAspect(auditLogService, userService, JsonMapper.builder().build(), beans.getBeanProvider(ApiKeyRepository.class));
        onRequest("/api/v1/users/5", "hrva_audited");
        final var audited = invoking(UrlController.class, "updateUrl", 265L, UrlUpdateDto.builder().id(265L).visitLimit(10L).build());

        keyAware.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("admin@hrva.cc", AuditAction.UPDATE_URL, "Url", "265", "visitLimit: 10");
        verifyNoInteractions(apiKeyRepository);
    }

    private static void onRequest(final String path, final String apiKey) {
        onRequest("PUT", path, apiKey);
    }

    private static void onRequest(final String method, final String path, final String apiKey) {
        final var request = new MockHttpServletRequest(method, path);
        request.addHeader("X-Api-Key", apiKey);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private Audited invoking(final Class<?> controller, final String method, final Object... args) {
        lenient().doReturn(controller).when(signature).getDeclaringType();
        when(joinPoint.getArgs()).thenReturn(args);
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(method))
                .findFirst()
                .map(candidate -> candidate.getAnnotation(Audited.class))
                .orElseThrow();
    }

    @Test
    void describesOnlyTheSubmittedFieldsOfAnUpdate() throws Throwable {
        final var audited = invoking(UrlController.class, "updateUrl", 265L, UrlUpdateDto.builder()
                .id(265L)
                .longUrl("https://new.example/page")
                .visitLimit(10L)
                .tags(Set.of("work"))
                .build());

        aspect.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("admin@hrva.cc", AuditAction.UPDATE_URL, "Url", "265",
                "longUrl: https://new.example/page, tags: [work], visitLimit: 10");
    }

    @Test
    void recordsWhatWasSubmittedEvenIfTheActionChangesItsArguments() throws Throwable {
        final var update = UrlUpdateDto.builder().id(265L).visitLimit(10L).build();
        final var audited = invoking(UrlController.class, "updateUrl", 265L, update);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            update.setVisitLimit(99L);
            return null;
        });

        aspect.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("admin@hrva.cc", AuditAction.UPDATE_URL, "Url", "265", "visitLimit: 10");
    }

    @Test
    void neverRecordsTheLinkPassword() throws Throwable {
        final var audited = invoking(UrlController.class, "updateUrl", 265L, UrlUpdateDto.builder().id(265L).password("s3cret").build());

        aspect.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("admin@hrva.cc", AuditAction.UPDATE_URL, "Url", "265", "password: changed");
    }

    @Test
    void recordsThatTheLinkPasswordWasRemoved() throws Throwable {
        final var audited = invoking(UrlController.class, "updateUrl", 265L, UrlUpdateDto.builder().id(265L).password("").build());

        aspect.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("admin@hrva.cc", AuditAction.UPDATE_URL, "Url", "265", "password: removed");
    }

    @Test
    void describesAUserUpdate() throws Throwable {
        final var audited = invoking(UserController.class, "updateUser", 26L, UserUpdateDto.builder().id(26L).active(false).build());

        aspect.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("admin@hrva.cc", AuditAction.UPDATE_USER, "User", "26", "active: false");
    }

    @Test
    void leavesDetailsEmptyWhenTheTargetIsTheOnlyArgument() throws Throwable {
        final var audited = invoking(UrlController.class, "revokeUrl", 12L);

        aspect.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("admin@hrva.cc", AuditAction.REVOKE_URL, "Url", "12", "");
    }

    @Test
    void namesAdminControllersByWhatTheyManage() throws Throwable {
        final var audited = invoking(AdminEmailLogController.class, "resendEmail", 7L);

        aspect.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("admin@hrva.cc", AuditAction.RESEND_EMAIL, "EmailLog", "7", "");
    }

    @Test
    void aDomainLimitIsRecordedAgainstTheUserItBelongsTo() throws Throwable {
        final var audited = invoking(AdminCustomDomainController.class, "removeAllowance", 5L);

        aspect.logAdminAction(joinPoint, audited);

        verify(auditLogService).log("admin@hrva.cc", AuditAction.REMOVE_DOMAIN_ALLOWANCE, "User", "5", "");
    }

    @Test
    void recordsAFailedActionWithItsReason() throws Throwable {
        final var audited = invoking(UrlController.class, "updateUrl", 9L, UrlUpdateDto.builder().id(9L).visitLimit(1L).build());
        when(joinPoint.proceed()).thenThrow(new UrlNotFoundException("Url with id 9 doesn't exist"));

        assertThatThrownBy(() -> aspect.logAdminAction(joinPoint, audited)).isInstanceOf(UrlNotFoundException.class);

        verify(auditLogService).logFailure("admin@hrva.cc", AuditAction.UPDATE_URL, "Url", "9", "Url with id 9 doesn't exist");
    }

}
