package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeySecondStepDto;
import cc.hrva.urlshortener.dto.PendingTokenDto;
import cc.hrva.urlshortener.dto.TotpSecondStepDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.exception.TwoStepFailedException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.anon.RateLimiterUnavailableException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.PasskeySignInStatsRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.SecondStepService;
import cc.hrva.urlshortener.service.SignInService;
import cc.hrva.urlshortener.service.TwoFactorService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.passkey.PasskeyAssertions;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonies;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremony;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyStore;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import cc.hrva.urlshortener.service.twostep.PendingLogin;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultSecondStepService implements SecondStepService {

    private static final String PENDING_LOGIN_ATTEMPTS_PREFIX = "pending-login:";
    private static final String PASSKEY_OPTIONS_RATE_LIMIT_NAME = "passkey-second-step-options";
    private static final String INVALID_CODE = "Invalid two-factor code";
    private static final String NO_PASSKEYS = "This account has no passkeys or security keys.";

    private final ClientIpResolver clientIpResolver;
    private final LoginAttemptService loginAttemptService;
    private final PendingLoginStore pendingLoginStore;
    private final RateLimiter rateLimiter;
    private final UserRepository userRepository;
    private final TwoFactorService twoFactorService;
    private final SignInService signInService;
    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final PasskeyCeremonies passkeyCeremonies;
    private final PasskeyCeremonyStore passkeyCeremonyStore;
    private final PasskeyOptionsFactory passkeyOptionsFactory;
    private final PasskeyAssertions passkeyAssertions;
    private final PasskeySignInStatsRepository passkeySignInStatsRepository;
    private final AnalyticsDays analyticsDays;

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

    @Override
    public PasskeyOptionsResponse passkeyOptions(final PendingTokenDto dto, final HttpServletRequest request) {
        final var clientIp = allowedClientIp(request);
        passkeyCeremonies.checkOptionsLimit(PASSKEY_OPTIONS_RATE_LIMIT_NAME, clientIp);
        final var user = userOf(pendingLoginOf(dto.getPendingToken(), clientIp), clientIp);
        final var passkeys = passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user);
        if (passkeys.isEmpty()) {
            throw new ApiException(NO_PASSKEYS);
        }

        return passkeyCeremonies.startRequest(PasskeyCeremonyPurpose.SECOND_STEP, passkeyOptionsFactory.descriptors(passkeys),
                user.getId());
    }

    @Override
    @Transactional
    public ResponseEntity<JWTTokenDto> completeWithPasskey(final PasskeySecondStepDto dto, final HttpServletRequest request) {
        final var attempt = startSecondStep(dto.getPendingToken(), request);
        final var ceremony = passkeyCeremonyStore.consume(dto.getCeremonyId())
                .filter(stored -> stored.purpose() == PasskeyCeremonyPurpose.SECOND_STEP)
                .filter(stored -> Objects.equals(stored.userId(), attempt.user().getId()))
                .orElseThrow(() -> expired(attempt.clientIp(), PasskeyCeremonyStore.EXPIRED));
        verifyPasskey(ceremony, dto.getCredential(), attempt);

        final var response = finishSecondStep(dto.getPendingToken(), attempt.user());
        passkeySignInStatsRepository.increment(analyticsDays.today());

        return response;
    }

    private SecondStepAttempt startSecondStep(final String pendingToken, final HttpServletRequest request) {
        final var clientIp = allowedClientIp(request);
        final var pendingLogin = pendingLoginOf(pendingToken, clientIp);
        countAttempt(pendingToken, pendingLogin, clientIp);

        final var user = userOf(pendingLogin, clientIp);
        if (loginAttemptService.isBlocked(twoFactorAttemptsKey(user))) {
            throw new NoAuthorizationException(LoginAttemptService.TWO_FACTOR_BLOCKED);
        }

        return new SecondStepAttempt(user, clientIp);
    }

    private String allowedClientIp(final HttpServletRequest request) {
        final var clientIp = clientIpResolver.getClientIp(request);
        if (loginAttemptService.isBlocked(clientIp)) {
            throw new NoAuthorizationException(LoginAttemptService.NETWORK_BLOCKED);
        }

        return clientIp;
    }

    private PendingLogin pendingLoginOf(final String pendingToken, final String clientIp) {
        return pendingLoginStore.find(pendingToken).orElseThrow(() -> expired(clientIp, SignInExpiredException.SIGN_IN_EXPIRED));
    }

    private User userOf(final PendingLogin pendingLogin, final String clientIp) {
        return userRepository.findById(pendingLogin.userId()).orElseThrow(() -> expired(clientIp, SignInExpiredException.SIGN_IN_EXPIRED));
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
            throw expired(clientIp, SignInExpiredException.SIGN_IN_EXPIRED);
        }
    }

    private void verifyPasskey(final PasskeyCeremony ceremony, final JsonNode credential, final SecondStepAttempt attempt) {
        try {
            passkeyAssertions.verify(ceremony, credential, attempt.user());
        } catch (final PasskeyVerificationException | ApiException exception) {
            secondStepFailed(attempt);
            throw exception;
        }
    }

    private SignInExpiredException expired(final String clientIp, final String message) {
        loginAttemptService.loginFailed(clientIp);

        return new SignInExpiredException(message);
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
