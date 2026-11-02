package cc.hrva.urlshortener.audit;

import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.security.ApiKeyRateLimitFilter;
import cc.hrva.urlshortener.security.ApiKeyTokens;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.UserService;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Aspect
@Component
@RequiredArgsConstructor
public class AdminAuditAspect {

    private static final String ADMIN_PREFIX = "Admin";
    private static final String ID = "id";
    private static final String ANONYMOUS = "anonymous";
    private static final String LINK_API = "/api/v1/urls";
    private static final Set<String> UNAUDITED_FIELDS = Set.of(ID, "url", "fallbackUrls");

    private final AuditLogService auditLogService;
    private final UserService userService;
    private final JsonMapper jsonMapper;
    private final ObjectProvider<ApiKeyRepository> apiKeyRepository;

    @Around("@annotation(audited)")
    public Object logAdminAction(final ProceedingJoinPoint joinPoint, final Audited audited) throws Throwable {
        final var performedBy = apiKeyOwner().orElseGet(this::signedInEmail);
        final var signature = joinPoint.getSignature();
        final var args = joinPoint.getArgs();
        final var targetType = audited.targetType().isEmpty() ? targetType(signature.getDeclaringType()) : audited.targetType();
        final var targeted = args.length > 0 && args[0] != null && BeanUtils.isSimpleValueType(args[0].getClass());
        final var targetIdentifier = targeted ? String.valueOf(args[0]) : "";
        final var details = describeChanges(args, targeted ? 1 : 0);

        try {
            final var result = joinPoint.proceed();
            auditLogService.log(performedBy, audited.value(), targetType, targeted ? targetIdentifier : createdIdentifier(result), details);
            return result;
        } catch (final Throwable e) {
            auditLogService.logFailure(performedBy, audited.value(), targetType, targetIdentifier, e.getMessage());
            throw e;
        }
    }

    private String signedInEmail() {
        final var user = userService.getUserFromToken();
        return user != null ? user.getEmail() : ANONYMOUS;
    }

    private Optional<String> apiKeyOwner() {
        final var repository = apiKeyRepository.getIfAvailable();
        if (repository == null || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        final var request = attributes.getRequest();
        final var key = request.getHeader(ApiKeyRateLimitFilter.API_KEY_HEADER);
        if (StringUtils.isBlank(key) || !StringUtils.startsWith(StringUtils.removeStart(request.getRequestURI(), request.getContextPath()), LINK_API)) {
            return Optional.empty();
        }
        final var hash = ApiKeyTokens.hash(key);
        return repository.findApiKeyByKeyHash(hash)
                .filter(found -> ApiKeyTokens.sameHash(found.getKeyHash(), hash) && found.getOwner() != null)
                .map(found -> found.getOwner().getEmail());
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
                .filter(field -> field.getValue() != null && !UNAUDITED_FIELDS.contains(field.getKey()))
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
