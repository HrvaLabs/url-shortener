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
        aspect = new AdminAuditAspect(auditLogService, userService, JsonMapper.builder().build());
        when(userService.getUserFromToken()).thenReturn(User.builder().email("admin@hrva.cc").build());
        when(joinPoint.getSignature()).thenReturn(signature);
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
