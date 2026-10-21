package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.exception.BlocklistSourceNotFoundException;
import cc.hrva.urlshortener.exception.CommonException;
import cc.hrva.urlshortener.exception.ConnectRequestAlreadyApprovedException;
import cc.hrva.urlshortener.exception.ConnectRequestExpiredException;
import cc.hrva.urlshortener.exception.ConnectedAppNotFoundException;
import cc.hrva.urlshortener.exception.DomainRuleNotFoundException;
import cc.hrva.urlshortener.exception.EmailExistsException;
import cc.hrva.urlshortener.exception.EmailLogNotFoundException;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.InvalidApiKeyException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(final MethodArgumentNotValidException ex) {
        final var errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : "Invalid value",
                        (a, b) -> b));
        return ResponseEntity.badRequest().body(errors);
    }

    private static String safeMessage(final Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : "Bad request";
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolation(final ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(buildViolationBody(ex));
    }

    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<Map<String, String>> handleTransactionSystem(final TransactionSystemException ex) {
        Throwable cause = ex.getRootCause();
        if (cause instanceof ConstraintViolationException cve) {
            return ResponseEntity.badRequest().body(buildViolationBody(cve));
        }
        log.error("Unhandled transaction exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
    }

    private static Map<String, String> buildViolationBody(final ConstraintViolationException ex) {
        final var violations = ex.getConstraintViolations();
        if (violations == null || violations.isEmpty()) {
            return Map.of("error", safeMessage(ex));
        }
        return violations.stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        v -> v.getMessage() != null ? v.getMessage() : "Invalid value",
                        (a, b) -> b));
    }

    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(final UrlNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(NoAuthorizationException.class)
    public ResponseEntity<Map<String, String>> handleUnauthorized(final NoAuthorizationException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(final AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApiException(final ApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUserNotFound(final UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(ApiKeyNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleApiKeyNotFound(final ApiKeyNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(EmailLogNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleEmailLogNotFound(final EmailLogNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(BlocklistSourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleBlocklistSourceNotFound(final BlocklistSourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(DomainRuleNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleDomainRuleNotFound(final DomainRuleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(EmailExistsException.class)
    public ResponseEntity<Map<String, String>> handleEmailExists(final EmailExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(ShortUrlAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> handleShortUrlExists(final ShortUrlAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<Map<String, String>> handleInvalidApiKey(final InvalidApiKeyException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(final BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid email or password"));
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<Map<String, String>> handleDisabled(final DisabledException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Your account is deactivated. Contact an administrator."));
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<Map<String, String>> handleEmailNotVerified(final EmailNotVerifiedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(ConnectRequestExpiredException.class)
    public ResponseEntity<Map<String, String>> handleConnectRequestExpired(final ConnectRequestExpiredException ex) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(ConnectRequestAlreadyApprovedException.class)
    public ResponseEntity<Map<String, String>> handleConnectRequestAlreadyApproved(final ConnectRequestAlreadyApprovedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(ConnectedAppNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleConnectedAppNotFound(final ConnectedAppNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleRateLimitExceeded(final RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResourceFound(final NoResourceFoundException ex) {
        log.info("No handler found method={}", ex.getHttpMethod());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethodNotSupported(final HttpRequestMethodNotSupportedException ex) {
        log.info("Method not supported method={}", ex.getMethod());
        final var supportedMethods = ex.getSupportedHttpMethods();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(supportedMethods == null ? new HttpMethod[0] : supportedMethods.toArray(HttpMethod[]::new))
                .body(Map.of("error", "Method not allowed"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMessageNotReadable(final HttpMessageNotReadableException ex) {
        log.info("Malformed request body");
        return ResponseEntity.badRequest().body(Map.of("error", "Malformed request body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleArgumentTypeMismatch(final MethodArgumentTypeMismatchException ex) {
        log.info("Invalid request parameter name={}", ex.getName());
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid value for " + ex.getName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParameter(final MissingServletRequestParameterException ex) {
        log.info("Missing request parameter name={}", ex.getParameterName());
        return ResponseEntity.badRequest().body(Map.of("error", "Missing parameter " + ex.getParameterName()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMediaTypeNotSupported(final HttpMediaTypeNotSupportedException ex) {
        log.info("Media type not supported contentType={}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .header(HttpHeaders.ACCEPT, MediaType.toString(ex.getSupportedMediaTypes()))
                .body(Map.of("error", "Unsupported media type"));
    }

    @ExceptionHandler(CommonException.class)
    public ResponseEntity<Map<String, String>> handleCommonException(final CommonException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", safeMessage(ex)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(final Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
    }

}
