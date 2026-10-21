package cc.hrva.urlshortener.audit;

import cc.hrva.urlshortener.controller.AdminEmailLogController;
import cc.hrva.urlshortener.controller.UrlController;
import cc.hrva.urlshortener.controller.UserController;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.UserService;
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

    private void invoking(final Class<?> controller, final String method, final Object... args) {
        doReturn(controller).when(signature).getDeclaringType();
        when(signature.getName()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
    }

    @Test
    void describesOnlyTheSubmittedFieldsOfAnUpdate() throws Throwable {
        invoking(UrlController.class, "updateUrl", 265L, UrlUpdateDto.builder()
                .id(265L)
                .longUrl("https://new.example/page")
                .visitLimit(10L)
                .tags(Set.of("work"))
                .build());

        aspect.logAdminAction(joinPoint);

        verify(auditLogService).log("admin@hrva.cc", "updateUrl", "Url", "265",
                "longUrl: https://new.example/page, tags: [work], visitLimit: 10");
    }

    @Test
    void recordsWhatWasSubmittedEvenIfTheActionChangesItsArguments() throws Throwable {
        final var update = UrlUpdateDto.builder().id(265L).visitLimit(10L).build();
        invoking(UrlController.class, "updateUrl", 265L, update);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            update.setVisitLimit(99L);
            return null;
        });

        aspect.logAdminAction(joinPoint);

        verify(auditLogService).log("admin@hrva.cc", "updateUrl", "Url", "265", "visitLimit: 10");
    }

    @Test
    void neverRecordsTheLinkPassword() throws Throwable {
        invoking(UrlController.class, "updateUrl", 265L, UrlUpdateDto.builder().id(265L).password("s3cret").build());

        aspect.logAdminAction(joinPoint);

        verify(auditLogService).log("admin@hrva.cc", "updateUrl", "Url", "265", "password: changed");
    }

    @Test
    void recordsThatTheLinkPasswordWasRemoved() throws Throwable {
        invoking(UrlController.class, "updateUrl", 265L, UrlUpdateDto.builder().id(265L).password("").build());

        aspect.logAdminAction(joinPoint);

        verify(auditLogService).log("admin@hrva.cc", "updateUrl", "Url", "265", "password: removed");
    }

    @Test
    void describesAUserUpdate() throws Throwable {
        invoking(UserController.class, "updateUser", 26L, UserUpdateDto.builder().id(26L).active(false).build());

        aspect.logAdminAction(joinPoint);

        verify(auditLogService).log("admin@hrva.cc", "updateUser", "User", "26", "active: false");
    }

    @Test
    void leavesDetailsEmptyWhenTheTargetIsTheOnlyArgument() throws Throwable {
        invoking(UrlController.class, "revokeUrl", 12L);

        aspect.logAdminAction(joinPoint);

        verify(auditLogService).log("admin@hrva.cc", "revokeUrl", "Url", "12", "");
    }

    @Test
    void namesAdminControllersByWhatTheyManage() throws Throwable {
        invoking(AdminEmailLogController.class, "resendEmail", 7L);

        aspect.logAdminAction(joinPoint);

        verify(auditLogService).log("admin@hrva.cc", "resendEmail", "EmailLog", "7", "");
    }

    @Test
    void recordsAFailedActionWithItsReason() throws Throwable {
        invoking(UrlController.class, "updateUrl", 9L, UrlUpdateDto.builder().id(9L).visitLimit(1L).build());
        when(joinPoint.proceed()).thenThrow(new UrlNotFoundException("Url with id 9 doesn't exist"));

        assertThatThrownBy(() -> aspect.logAdminAction(joinPoint)).isInstanceOf(UrlNotFoundException.class);

        verify(auditLogService).log("admin@hrva.cc", "updateUrl_FAILED", "Url", "9", "Url with id 9 doesn't exist");
    }

}
