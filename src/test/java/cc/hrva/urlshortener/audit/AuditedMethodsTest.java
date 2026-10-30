package cc.hrva.urlshortener.audit;

import cc.hrva.urlshortener.UrlShortenerApplication;
import cc.hrva.urlshortener.model.enums.AuditAction;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

class AuditedMethodsTest {

    private static final Map<String, AuditAction> AUDITED = Map.ofEntries(
            Map.entry("AdminController.clearLoginAttempts", AuditAction.CLEAR_LOGIN_ATTEMPTS),
            Map.entry("AdminEmailLogController.resendEmail", AuditAction.RESEND_EMAIL),
            Map.entry("UserController.updateUser", AuditAction.UPDATE_USER),
            Map.entry("UserController.deleteUser", AuditAction.DELETE_USER),
            Map.entry("UrlController.updateUrl", AuditAction.UPDATE_URL),
            Map.entry("UrlController.revokeUrl", AuditAction.REVOKE_URL),
            Map.entry("UrlController.activateUrl", AuditAction.ACTIVATE_URL),
            Map.entry("UrlController.deleteUrl", AuditAction.DELETE_URL),
            Map.entry("AdminBlocklistController.createSource", AuditAction.CREATE_SOURCE),
            Map.entry("AdminBlocklistController.updateSource", AuditAction.UPDATE_SOURCE),
            Map.entry("AdminBlocklistController.deleteSource", AuditAction.DELETE_SOURCE),
            Map.entry("AdminBlocklistController.refreshSource", AuditAction.REFRESH_SOURCE),
            Map.entry("AdminBioPageController.hideBioPage", AuditAction.HIDE_BIO_PAGE),
            Map.entry("AdminBioPageController.unhideBioPage", AuditAction.UNHIDE_BIO_PAGE),
            Map.entry("AdminBioPageController.changeBioPageHandle", AuditAction.RENAME_BIO_HANDLE),
            Map.entry("AdminBioPageController.revokeBioPageHandle", AuditAction.REVOKE_BIO_HANDLE),
            Map.entry("AdminBioPageReviewController.resolveBioPageReview", AuditAction.RESOLVE_BIO_REVIEW),
            Map.entry("AdminCustomDomainController.approveDomain", AuditAction.APPROVE_DOMAIN),
            Map.entry("AdminCustomDomainController.rejectDomain", AuditAction.REJECT_DOMAIN),
            Map.entry("AdminCustomDomainController.retryDomain", AuditAction.RETRY_DOMAIN),
            Map.entry("AdminCustomDomainController.disableDomain", AuditAction.DISABLE_DOMAIN),
            Map.entry("AdminCustomDomainController.enableDomain", AuditAction.ENABLE_DOMAIN),
            Map.entry("AdminCustomDomainController.deleteDomain", AuditAction.DELETE_DOMAIN),
            Map.entry("AdminCustomDomainController.setAllowance", AuditAction.SET_DOMAIN_ALLOWANCE),
            Map.entry("AdminCustomDomainController.removeAllowance", AuditAction.REMOVE_DOMAIN_ALLOWANCE));

    @Test
    void exactlyTheseControllerMethodsAreAuditedEachWithItsOwnAction() {
        assertThat(auditedControllerMethods()).isEqualTo(AUDITED);
    }

    @Test
    void noControllerMethodIsAuditedAsUnknown() {
        assertThat(auditedControllerMethods()).doesNotContainValue(AuditAction.UNKNOWN);
    }

    private static Map<String, AuditAction> auditedControllerMethods() {
        final var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(UrlShortenerApplication.class.getPackageName()).stream()
                .map(candidate -> ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()), null))
                .flatMap(controller -> Arrays.stream(controller.getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(Audited.class))
                        .map(method -> Map.entry(controller.getSimpleName() + "." + method.getName(), method.getAnnotation(Audited.class).value())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

}
