package cc.hrva.urlshortener.audit;

import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.UserService;
import java.util.Arrays;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.BeanUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Aspect
@Component
@RequiredArgsConstructor
public class AdminAuditAspect {

    private static final String ADMIN_PREFIX = "Admin";
    private static final String ID = "id";

    private final AuditLogService auditLogService;
    private final UserService userService;
    private final JsonMapper jsonMapper;

    @Around("execution(* cc.hrva.urlshortener.controller.AdminController.clearLoginAttempts(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.AdminEmailLogController.resendEmail(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.UserController.deleteUser(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.UserController.updateUser(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.UrlController.revokeUrl(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.UrlController.activateUrl(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.UrlController.deleteUrl(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.UrlController.updateUrl(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.AdminBlocklistController.createSource(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.AdminBlocklistController.updateSource(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.AdminBlocklistController.deleteSource(..)) || " +
            "execution(* cc.hrva.urlshortener.controller.AdminBlocklistController.refreshSource(..))")
    public Object logAdminAction(final ProceedingJoinPoint joinPoint) throws Throwable {
        final var user = userService.getUserFromToken();
        final var performedBy = user != null ? user.getEmail() : "anonymous";
        final var signature = joinPoint.getSignature();
        final var methodName = signature.getName();
        final var args = joinPoint.getArgs();
        final var targetType = targetType(signature.getDeclaringType());
        final var targeted = args.length > 0 && args[0] != null && BeanUtils.isSimpleValueType(args[0].getClass());
        final var targetIdentifier = targeted ? String.valueOf(args[0]) : "";
        final var details = describeChanges(args, targeted ? 1 : 0);

        try {
            final var result = joinPoint.proceed();
            auditLogService.log(performedBy, methodName, targetType, targeted ? targetIdentifier : createdIdentifier(result), details);
            return result;
        } catch (final Throwable e) {
            auditLogService.log(performedBy, methodName + "_FAILED", targetType, targetIdentifier, e.getMessage());
            throw e;
        }
    }

    private static String targetType(final Class<?> controller) {
        final var name = controller.getSimpleName().replace("Controller", "");
        return name.startsWith(ADMIN_PREFIX) && name.length() > ADMIN_PREFIX.length()
                ? name.substring(ADMIN_PREFIX.length())
                : name;
    }

    private String createdIdentifier(final Object result) {
        if (!(result instanceof ResponseEntity<?> response) || response.getBody() == null) {
            return "";
        }
        final var id = jsonMapper.valueToTree(response.getBody()).path(ID);
        return id.isValueNode() ? id.asString() : "";
    }

    private String describeChanges(final Object[] args, final int targetArguments) {
        return Arrays.stream(args)
                .skip(targetArguments)
                .filter(Objects::nonNull)
                .map(this::describe)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.joining(", "));
    }

    private String describe(final Object arg) {
        if (BeanUtils.isSimpleValueType(arg.getClass())) {
            return String.valueOf(arg);
        }

        final var fields = jsonMapper.convertValue(arg, new TypeReference<TreeMap<String, Object>>() {});
        return fields.entrySet().stream()
                .filter(field -> field.getValue() != null && !ID.equals(field.getKey()))
                .map(field -> field.getKey() + ": " + describeValue(field.getKey(), field.getValue()))
                .collect(Collectors.joining(", "));
    }

    private static String describeValue(final String field, final Object value) {
        if ("password".equals(field)) {
            return value.toString().isEmpty() ? "removed" : "changed";
        }
        return String.valueOf(value);
    }

}
