package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.RemovePasswordDto;
import cc.hrva.urlshortener.dto.SignInMethodsResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.SignInMethodNotFoundException;
import cc.hrva.urlshortener.model.SignInMethodState;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.UserSignInMethodRepository;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.security.StoredPasswords;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.SignInMethodChangeService;
import cc.hrva.urlshortener.service.SignInMethodService;
import cc.hrva.urlshortener.service.signin.SignInMethodChangedEvent;
import cc.hrva.urlshortener.service.signin.SignInMethodGuard;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultSignInMethodChangeService implements SignInMethodChangeService {

    private static final String TARGET_USER = "User";
    private static final String NOT_AUTHENTICATED = "Not authenticated";
    private static final String NOT_A_PROVIDER = "Only Google and GitHub sign-in can be turned off or on here.";
    private static final String NEVER_USED = "This account has never signed in with %s.";
    private static final String NO_PASSWORD = "This account has no password to remove.";
    private static final String CURRENT_PASSWORD = "currentPassword";
    private static final String WRONG_PASSWORD = "That password isn't right.";

    private final UserRepository userRepository;
    private final UserSignInMethodRepository userSignInMethodRepository;
    private final AccountLock accountLock;
    private final SignInMethodService signInMethodService;
    private final SignInMethodGuard signInMethodGuard;
    private final Reauthentication reauthentication;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public SignInMethodsResponse turnOff(final SignInMethod method) {
        requireProvider(method);
        final var user = accountLock.lock(currentUser());
        if (stateOf(user, method).turnedOff()) {
            return methodsOf(user);
        }

        final var before = signInMethodGuard.waysOf(user);
        signInMethodGuard.check(user, before, before.withoutProvider(method));
        final var turnedOffAt = LocalDateTime.now();
        signInMethodService.turnOff(user.getId(), method, turnedOffAt);
        auditLogService.log(user.getEmail(), AuditAction.TURN_OFF_SIGN_IN_METHOD, TARGET_USER, String.valueOf(user.getId()), detailsOf(method));
        eventPublisher.publishEvent(SignInMethodChangedEvent.turnedOff(user, method, turnedOffAt));
        log.info("Sign-in method turned off user id={} method={}", user.getId(), method);

        return methodsOf(user);
    }

    @Override
    @Transactional
    public SignInMethodsResponse turnOn(final SignInMethod method) {
        requireProvider(method);
        final var user = accountLock.lock(currentUser());
        if (!stateOf(user, method).turnedOff()) {
            return methodsOf(user);
        }

        signInMethodService.turnOn(user.getId(), method);
        auditLogService.log(user.getEmail(), AuditAction.TURN_ON_SIGN_IN_METHOD, TARGET_USER, String.valueOf(user.getId()), detailsOf(method));
        eventPublisher.publishEvent(SignInMethodChangedEvent.turnedBackOn(user, method, LocalDateTime.now()));
        log.info("Sign-in method turned back on user id={} method={}", user.getId(), method);

        return methodsOf(user);
    }

    @Override
    @Transactional
    public SignInMethodsResponse removePassword(final RemovePasswordDto dto) {
        final var user = accountLock.lock(currentUser());
        final var before = signInMethodGuard.waysOf(user);
        if (!before.password()) {
            throw new SignInMethodNotFoundException(NO_PASSWORD);
        }
        if (!reauthentication.currentPasswordMatches(user, dto.getCurrentPassword())) {
            log.info("Password not removed, the current password didn't match user id={}", user.getId());
            throw new FieldValidationException(CURRENT_PASSWORD, WRONG_PASSWORD);
        }

        signInMethodGuard.check(user, before, before.withoutPassword());
        final var removedAt = LocalDateTime.now();
        userRepository.replacePassword(user.getId(), StoredPasswords.none());
        signInMethodService.turnOff(user.getId(), SignInMethod.PASSWORD, removedAt);
        auditLogService.log(user.getEmail(), AuditAction.REMOVE_PASSWORD, TARGET_USER, String.valueOf(user.getId()),
                detailsOf(SignInMethod.PASSWORD));
        eventPublisher.publishEvent(SignInMethodChangedEvent.passwordRemoved(user, removedAt));
        log.info("Password removed user id={}", user.getId());

        return methodsOf(user);
    }

    @Override
    @Transactional
    public void passwordWasReset(final User lockedUser) {
        if (!signInMethodService.turnOn(lockedUser.getId(), SignInMethod.PASSWORD)) {
            return;
        }

        auditLogService.log(lockedUser.getEmail(), AuditAction.TURN_ON_SIGN_IN_METHOD, TARGET_USER, String.valueOf(lockedUser.getId()),
                detailsOf(SignInMethod.PASSWORD) + ", via: password reset");
        log.info("Password sign-in turned back on by a password reset user id={}", lockedUser.getId());
    }

    private SignInMethodState stateOf(final User user, final SignInMethod method) {
        return userSignInMethodRepository.findStatesByUserId(user.getId()).stream()
                .filter(state -> state.method() == method)
                .findFirst()
                .orElseThrow(() -> new SignInMethodNotFoundException(NEVER_USED.formatted(SignInMethodChangedEvent.nameOf(method))));
    }

    private SignInMethodsResponse methodsOf(final User user) {
        return new SignInMethodsResponse(signInMethodService.methodsOf(user.getId()));
    }

    private User currentUser() {
        final var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new NoAuthorizationException(NOT_AUTHENTICATED);
        }

        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new NoAuthorizationException(NOT_AUTHENTICATED));
    }

    private static void requireProvider(final SignInMethod method) {
        if (!SignInMethodGuard.isProvider(method)) {
            throw new ApiException(NOT_A_PROVIDER);
        }
    }

    private static String detailsOf(final SignInMethod method) {
        return "method: " + method.name();
    }

}
