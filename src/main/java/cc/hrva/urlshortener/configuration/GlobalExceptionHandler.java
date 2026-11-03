package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.ApiKeyActiveException;
import cc.hrva.urlshortener.exception.ApiKeyInactiveException;
import cc.hrva.urlshortener.exception.DestinationChangeRefusedException;
import cc.hrva.urlshortener.exception.DestinationNeedsConfirmationException;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.exception.BioHandleUnavailableException;
import cc.hrva.urlshortener.exception.BioPageConflictException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.exception.BlocklistSourceNotFoundException;
import cc.hrva.urlshortener.exception.BulkBlockIncompleteException;
import cc.hrva.urlshortener.exception.CampaignNotFoundException;
import cc.hrva.urlshortener.exception.ChannelValidationException;
import cc.hrva.urlshortener.exception.CommonException;
import cc.hrva.urlshortener.exception.ConnectRequestAlreadyApprovedException;
import cc.hrva.urlshortener.exception.ConnectRequestExpiredException;
import cc.hrva.urlshortener.exception.ConnectedAppNotFoundException;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.DomainRuleNotFoundException;
import cc.hrva.urlshortener.exception.EmailLogNotFoundException;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.FeatureUnavailableException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.HumanCheckUnavailableException;
import cc.hrva.urlshortener.exception.InvalidApiKeyException;
import cc.hrva.urlshortener.exception.LinkClicksUsedUpException;
import cc.hrva.urlshortener.exception.LinkExpiredException;
import cc.hrva.urlshortener.exception.LastSignInMethodException;
import cc.hrva.urlshortener.exception.PasskeyNotFoundException;
import cc.hrva.urlshortener.exception.SignInMethodNotFoundException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ReauthenticationRequiredException;
import cc.hrva.urlshortener.exception.ReservedAliasNotFoundException;
import cc.hrva.urlshortener.exception.SettingNotFoundException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.ResetLinkExpiredException;
import cc.hrva.urlshortener.exception.ScreenshotsUnavailableException;
import cc.hrva.urlshortener.exception.VirusTotalUnavailableException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.exception.ServiceBusyException;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.exception.TwoStepFailedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.NotSignedInException;
import cc.hrva.urlshortener.security.AccessAnswers;
import cc.hrva.urlshortener.exception.NotificationChannelNotFoundException;
import cc.hrva.urlshortener.exception.NotificationTestUnavailableException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.util.DatabaseOutages;
import cc.hrva.urlshortener.util.ProblemResponses;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Duration DATABASE_RETRY = Duration.ofMinutes(1);
    private static final String MISSING = "missing";
    private static final String VERDICT = "verdict";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(final MethodArgumentNotValidException ex) {
        return ProblemResponses.entityWithErrors(HttpStatus.BAD_REQUEST, fieldErrors(ex.getBindingResult().getFieldErrors().stream()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(final HandlerMethodValidationException ex) {
        return ProblemResponses.entityWithErrors(HttpStatus.BAD_REQUEST, fieldErrors(ex.getBeanResults().stream()
                .flatMap(result -> result.getFieldErrors().stream())));
    }

    private static Map<String, String> fieldErrors(final Stream<FieldError> errors) {
        return errors.collect(Collectors.toMap(
                FieldError::getField,
                e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : "Invalid value",
                (a, b) -> b,
                LinkedHashMap::new));
    }

    private static String safeMessage(final Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : "Bad request";
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(final ConstraintViolationException ex) {
        return violations(ex);
    }

    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<ProblemDetail> handleTransactionSystem(final TransactionSystemException ex) {
        Throwable cause = ex.getRootCause();
        if (cause instanceof ConstraintViolationException cve) {
            return violations(cve);
        }
        if (DatabaseOutages.isOutage(ex)) {
            return databaseUnreachable(ex);
        }
        log.error("Unhandled transaction exception", ex);
        return ProblemResponses.entity(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private static ResponseEntity<ProblemDetail> violations(final ConstraintViolationException ex) {
        final var violations = ex.getConstraintViolations();
        if (violations == null || violations.isEmpty()) {
            return ProblemResponses.entity(HttpStatus.BAD_REQUEST, safeMessage(ex));
        }
        return ProblemResponses.entityWithErrors(HttpStatus.BAD_REQUEST, violations.stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        v -> v.getMessage() != null ? v.getMessage() : "Invalid value",
                        (a, b) -> b,
                        LinkedHashMap::new)));
    }

    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(final UrlNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(NoAuthorizationException.class)
    public ResponseEntity<ProblemDetail> handleUnauthorized(final NoAuthorizationException ex) {
        return ProblemResponses.entity(HttpStatus.FORBIDDEN, safeMessage(ex));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(final AccessDeniedException ex) {
        if (AccessAnswers.nobodySignedIn()) {
            return ProblemResponses.entity(HttpStatus.UNAUTHORIZED, AccessAnswers.SIGN_IN_TO_CONTINUE);
        }
        return ProblemResponses.entity(HttpStatus.FORBIDDEN, AccessAnswers.deniedDetail());
    }

    @ExceptionHandler(NotSignedInException.class)
    public ResponseEntity<ProblemDetail> handleNotSignedIn(final NotSignedInException ex) {
        return ProblemResponses.entity(HttpStatus.UNAUTHORIZED, safeMessage(ex));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(final ApiException ex) {
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, safeMessage(ex));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleUserNotFound(final UserNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(ApiKeyNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleApiKeyNotFound(final ApiKeyNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(EmailLogNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleEmailLogNotFound(final EmailLogNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(BlocklistSourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleBlocklistSourceNotFound(final BlocklistSourceNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(DomainRuleNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleDomainRuleNotFound(final DomainRuleNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(ReservedAliasNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleReservedAliasNotFound(final ReservedAliasNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(BioPageNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleBioPageNotFound(final BioPageNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(ShortUrlAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleShortUrlExists(final ShortUrlAlreadyExistsException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(BioHandleUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleBioHandleUnavailable(final BioHandleUnavailableException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(BioPageConflictException.class)
    public ResponseEntity<ProblemDetail> handleBioPageConflict(final BioPageConflictException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(ApiKeyActiveException.class)
    public ResponseEntity<ProblemDetail> handleApiKeyActive(final ApiKeyActiveException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(DestinationChangeRefusedException.class)
    public ResponseEntity<ProblemDetail> handleDestinationChangeRefused(final DestinationChangeRefusedException ex) {
        final var problem = ProblemResponses.of(HttpStatus.FORBIDDEN, safeMessage(ex));
        problem.setProperty(MISSING, ex.getMissing());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(DestinationNeedsConfirmationException.class)
    public ResponseEntity<ProblemDetail> handleDestinationNeedsConfirmation(final DestinationNeedsConfirmationException ex) {
        final var problem = ProblemResponses.of(HttpStatus.CONFLICT, safeMessage(ex));
        final var verdict = new LinkedHashMap<String, Object>();
        verdict.put("outcome", ex.getVerdict().outcome());
        verdict.put("reasons", ex.getVerdict().reasons());
        problem.setProperty(VERDICT, verdict);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(LinkClicksUsedUpException.class)
    public ResponseEntity<ProblemDetail> handleLinkClicksUsedUp(final LinkClicksUsedUpException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(ScreenshotsUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleScreenshotsUnavailable(final ScreenshotsUnavailableException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(VirusTotalUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleVirusTotalUnavailable(final VirusTotalUnavailableException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(LinkExpiredException.class)
    public ResponseEntity<ProblemDetail> handleLinkExpired(final LinkExpiredException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(ApiKeyInactiveException.class)
    public ResponseEntity<ProblemDetail> handleApiKeyInactive(final ApiKeyInactiveException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<ProblemDetail> handleInvalidApiKey(final InvalidApiKeyException ex) {
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, safeMessage(ex));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentials(final BadCredentialsException ex) {
        return ProblemResponses.entity(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ProblemDetail> handleDisabled(final DisabledException ex) {
        return ProblemResponses.entity(HttpStatus.FORBIDDEN, "Your account is deactivated. Contact an administrator.");
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ProblemDetail> handleEmailNotVerified(final EmailNotVerifiedException ex) {
        return ProblemResponses.entity(HttpStatus.FORBIDDEN, safeMessage(ex));
    }

    @ExceptionHandler(SignInExpiredException.class)
    public ResponseEntity<ProblemDetail> handleSignInExpired(final SignInExpiredException ex) {
        return ProblemResponses.entity(HttpStatus.GONE, safeMessage(ex));
    }

    @ExceptionHandler(ResetLinkExpiredException.class)
    public ResponseEntity<ProblemDetail> handleResetLinkExpired(final ResetLinkExpiredException ex) {
        return ProblemResponses.entity(HttpStatus.GONE, safeMessage(ex));
    }

    @ExceptionHandler(TwoStepFailedException.class)
    public ResponseEntity<ProblemDetail> handleTwoStepFailed(final TwoStepFailedException ex) {
        return ProblemResponses.entity(HttpStatus.UNAUTHORIZED, safeMessage(ex));
    }

    @ExceptionHandler(SignInUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleSignInUnavailable(final SignInUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, ProblemResponses.retryAfterSeconds(ex.getRetryAfter()))
                .body(ProblemResponses.of(HttpStatus.SERVICE_UNAVAILABLE, safeMessage(ex)));
    }

    @ExceptionHandler(PasskeyVerificationException.class)
    public ResponseEntity<ProblemDetail> handlePasskeyVerification(final PasskeyVerificationException ex) {
        return ProblemResponses.entity(HttpStatus.UNAUTHORIZED, safeMessage(ex));
    }

    @ExceptionHandler(ReauthenticationRequiredException.class)
    public ResponseEntity<ProblemDetail> handleReauthenticationRequired(final ReauthenticationRequiredException ex) {
        return ProblemResponses.entity(HttpStatus.UNAUTHORIZED, safeMessage(ex));
    }

    @ExceptionHandler(SignInMethodNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSignInMethodNotFound(final SignInMethodNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(LastSignInMethodException.class)
    public ResponseEntity<ProblemDetail> handleLastSignInMethod(final LastSignInMethodException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(PasskeyNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePasskeyNotFound(final PasskeyNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(ConnectRequestExpiredException.class)
    public ResponseEntity<ProblemDetail> handleConnectRequestExpired(final ConnectRequestExpiredException ex) {
        return ProblemResponses.entity(HttpStatus.GONE, safeMessage(ex));
    }

    @ExceptionHandler(ConnectRequestAlreadyApprovedException.class)
    public ResponseEntity<ProblemDetail> handleConnectRequestAlreadyApproved(final ConnectRequestAlreadyApprovedException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(ConnectedAppNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleConnectedAppNotFound(final ConnectedAppNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(NotificationChannelNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotificationChannelNotFound(final NotificationChannelNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(ChannelValidationException.class)
    public ResponseEntity<ProblemDetail> handleChannelValidation(final ChannelValidationException ex) {
        return ProblemResponses.entityWithErrors(HttpStatus.BAD_REQUEST, ex.getFieldErrors());
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ProblemDetail> handleFieldValidation(final FieldValidationException ex) {
        return ProblemResponses.entityWithErrors(HttpStatus.BAD_REQUEST, ex.getFieldErrors());
    }

    @ExceptionHandler(NotificationTestUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleNotificationTestUnavailable(final NotificationTestUnavailableException ex) {
        return ProblemResponses.entity(HttpStatus.SERVICE_UNAVAILABLE, safeMessage(ex));
    }

    @ExceptionHandler(CustomDomainNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleCustomDomainNotFound(final CustomDomainNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(SettingNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSettingNotFound(final SettingNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(CampaignNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleCampaignNotFound(final CampaignNotFoundException ex) {
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, safeMessage(ex));
    }

    @ExceptionHandler(CustomDomainConflictException.class)
    public ResponseEntity<ProblemDetail> handleCustomDomainConflict(final CustomDomainConflictException ex) {
        return ProblemResponses.entity(HttpStatus.CONFLICT, safeMessage(ex));
    }

    @ExceptionHandler(HumanCheckUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleHumanCheckUnavailable(final HumanCheckUnavailableException ex) {
        return ProblemResponses.entity(HttpStatus.SERVICE_UNAVAILABLE, safeMessage(ex));
    }

    @ExceptionHandler(FeatureUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleFeatureUnavailable(final FeatureUnavailableException ex) {
        return ProblemResponses.entity(HttpStatus.SERVICE_UNAVAILABLE, safeMessage(ex));
    }

    @ExceptionHandler(BulkBlockIncompleteException.class)
    public ResponseEntity<ProblemDetail> handleBulkBlockIncomplete(final BulkBlockIncompleteException ex) {
        return ProblemResponses.entity(HttpStatus.SERVICE_UNAVAILABLE, safeMessage(ex));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(final RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, ProblemResponses.retryAfterSeconds(ex.getRetryAfter()))
                .body(ProblemResponses.of(HttpStatus.TOO_MANY_REQUESTS, safeMessage(ex)));
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class, QueryTimeoutException.class,
            TransientDataAccessResourceException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnreachable(final Exception ex) {
        return databaseUnreachable(ex);
    }

    private static ResponseEntity<ProblemDetail> databaseUnreachable(final Exception ex) {
        log.warn("Database unreachable exception={}", ex.getClass().getName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, ProblemResponses.retryAfterSeconds(DATABASE_RETRY))
                .body(ProblemResponses.of(HttpStatus.SERVICE_UNAVAILABLE, DatabaseOutages.UNREACHABLE));
    }

    @ExceptionHandler(ServiceBusyException.class)
    public ResponseEntity<ProblemDetail> handleServiceBusy(final ServiceBusyException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, ProblemResponses.retryAfterSeconds(ex.getRetryAfter()))
                .body(ProblemResponses.of(HttpStatus.SERVICE_UNAVAILABLE, safeMessage(ex)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(final NoResourceFoundException ex) {
        log.info("No handler found method={}", ex.getHttpMethod());
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(final HttpRequestMethodNotSupportedException ex) {
        log.info("Method not supported method={}", ex.getMethod());
        final var supportedMethods = ex.getSupportedHttpMethods();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(supportedMethods == null ? new HttpMethod[0] : supportedMethods.toArray(HttpMethod[]::new))
                .body(ProblemResponses.of(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMessageNotReadable(final HttpMessageNotReadableException ex) {
        final var invalidFormat = ExceptionUtils.throwableOfType(ex, InvalidFormatException.class);
        if (invalidFormat != null && invalidFormat.getTargetType() != null && invalidFormat.getTargetType().isEnum()) {
            final var field = fieldOf(invalidFormat.getPath());
            log.info("Unknown value in request body field={}", field);
            return ProblemResponses.entity(HttpStatus.BAD_REQUEST, StringUtils.capitalize(field) + " must be " + oneOf(invalidFormat.getTargetType()));
        }
        log.info("Malformed request body");
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    private static String fieldOf(final List<JacksonException.Reference> path) {
        final var field = path.stream()
                .map(reference -> reference.getPropertyName() != null ? "." + reference.getPropertyName() : "[" + reference.getIndex() + "]")
                .collect(Collectors.joining());
        return StringUtils.defaultIfEmpty(StringUtils.removeStart(field, "."), "value");
    }

    private static String oneOf(final Class<?> enumType) {
        final var names = Arrays.stream(enumType.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList();
        return names.size() == 1
                ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.getLast();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleArgumentTypeMismatch(final MethodArgumentTypeMismatchException ex) {
        log.info("Invalid request parameter name={}", ex.getName());
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Invalid value for " + ex.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParameter(final MissingServletRequestParameterException ex) {
        log.info("Missing request parameter name={}", ex.getParameterName());
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Missing parameter " + ex.getParameterName());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(final HttpMediaTypeNotSupportedException ex) {
        log.info("Media type not supported contentType={}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .header(HttpHeaders.ACCEPT, MediaType.toString(ex.getSupportedMediaTypes()))
                .body(ProblemResponses.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"));
    }

    @ExceptionHandler(CommonException.class)
    public ResponseEntity<ProblemDetail> handleCommonException(final CommonException ex) {
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, safeMessage(ex));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(final Exception ex) {
        if (DatabaseOutages.isOutage(ex)) {
            return databaseUnreachable(ex);
        }
        log.error("Unhandled exception", ex);
        return ProblemResponses.entity(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

}
