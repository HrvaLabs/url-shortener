package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.TotpSecondStepDto;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.exception.TwoStepFailedException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.anon.RateLimiterUnavailableException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.SecondStepService;
import cc.hrva.urlshortener.service.SignInService;
import cc.hrva.urlshortener.service.TwoFactorService;
import cc.hrva.urlshortener.service.twostep.PendingLogin;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultSecondStepService implements SecondStepService {

    private static final String PENDING_LOGIN_ATTEMPTS_PREFIX = "pending-login:";
    private static final String INVALID_CODE = "Invalid two-factor code";

    private final ClientIpResolver clientIpResolver;
    private final LoginAttemptService loginAttemptService;
    private final PendingLoginStore pendingLoginStore;
    private final RateLimiter rateLimiter;
    private final UserRepository userRepository;
    private final TwoFactorService twoFactorService;
    private final SignInService signInService;

    @Override
    @Transactional
    public ResponseEntity<JWTTokenDto> completeWithCode(final TotpSecondStepDto dto, final HttpServletRequest request) {
        final var attempt = startSecondStep(dto.getPendingToken(), request);

        if (!twoFactorService.verifyLoginCode(attempt.user(), dto.getCode())) {
            secondStepFailed(attempt);
            throw new TwoStepFailedException(INVALID_CODE);
        }

        return finishSecondStep(dto.getPendingToken(), attempt.user());
    }

    private SecondStepAttempt startSecondStep(final String pendingToken, final HttpServletRequest request) {
        final var clientIp = clientIpResolver.getClientIp(request);
        if (loginAttemptService.isBlocked(clientIp)) {
            throw new NoAuthorizationException(LoginAttemptService.NETWORK_BLOCKED);
        }

        final var pendingLogin = pendingLoginStore.find(pendingToken).orElseThrow(() -> signInExpired(clientIp));
        countAttempt(pendingToken, pendingLogin, clientIp);

        final var user = userRepository.findById(pendingLogin.userId()).orElseThrow(() -> signInExpired(clientIp));
        if (loginAttemptService.isBlocked(twoFactorAttemptsKey(user))) {
            throw new NoAuthorizationException(LoginAttemptService.TWO_FACTOR_BLOCKED);
        }

        return new SecondStepAttempt(user, clientIp);
    }

    private void countAttempt(final String pendingToken, final PendingLogin pendingLogin, final String clientIp) {
        final boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(PENDING_LOGIN_ATTEMPTS_PREFIX + pendingToken, PendingLoginStore.MAX_ATTEMPTS, PendingLoginStore.TIME_TO_LIVE);
        } catch (final RateLimiterUnavailableException exception) {
            throw new SignInUnavailableException(PendingLoginStore.UNAVAILABLE, exception);
        }

        if (!allowed) {
            pendingLoginStore.discard(pendingToken);
            log.info("Second step out of attempts, sign-in discarded user id={}", pendingLogin.userId());
            throw signInExpired(clientIp);
        }
    }

    private SignInExpiredException signInExpired(final String clientIp) {
        loginAttemptService.loginFailed(clientIp);

        return new SignInExpiredException();
    }

    private void secondStepFailed(final SecondStepAttempt attempt) {
        loginAttemptService.loginFailed(twoFactorAttemptsKey(attempt.user()));
        loginAttemptService.loginFailed(attempt.clientIp());
        log.info("Second step failed user id={}", attempt.user().getId());
    }

    private ResponseEntity<JWTTokenDto> finishSecondStep(final String pendingToken, final User user) {
        final var pendingLogin = pendingLoginStore.consume(pendingToken).orElseThrow(SignInExpiredException::new);

        if (Boolean.FALSE.equals(user.getActive())) {
            throw new DisabledException("Account is deactivated");
        }
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new EmailNotVerifiedException();
        }

        loginAttemptService.loginSucceeded(twoFactorAttemptsKey(user));
        log.info("Second step completed user id={}", user.getId());

        return signInService.signIn(user, pendingLogin.rememberMe());
    }

    private static String twoFactorAttemptsKey(final User user) {
        return LoginAttemptService.TWO_FACTOR_PREFIX + user.getId();
    }

    private record SecondStepAttempt(User user, String clientIp) {
    }

}
