package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeySignInDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.PasskeySignInStatsRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.PasskeySignInService;
import cc.hrva.urlshortener.service.SignInService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.passkey.PasskeyAssertions;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonies;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremony;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultPasskeySignInService implements PasskeySignInService {

    private static final String OPTIONS_RATE_LIMIT_NAME = "passkey-options";

    private final ClientIpResolver clientIpResolver;
    private final LoginAttemptService loginAttemptService;
    private final PasskeyCeremonies passkeyCeremonies;
    private final PasskeyCeremonyStore passkeyCeremonyStore;
    private final PasskeyAssertions passkeyAssertions;
    private final PasskeySignInStatsRepository passkeySignInStatsRepository;
    private final AnalyticsDays analyticsDays;
    private final SignInService signInService;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PasskeyOptionsResponse options(final HttpServletRequest request) {
        passkeyCeremonies.checkOptionsLimit(OPTIONS_RATE_LIMIT_NAME, clientIpResolver.getClientIp(request));

        return passkeyCeremonies.startRequest(PasskeyCeremonyPurpose.SIGN_IN, List.of(), null);
    }

    @Override
    @Transactional
    public ResponseEntity<JWTTokenDto> verify(final PasskeySignInDto dto, final HttpServletRequest request) {
        final var clientIp = clientIpResolver.getClientIp(request);
        if (loginAttemptService.isBlocked(clientIp)) {
            throw new NoAuthorizationException(LoginAttemptService.NETWORK_BLOCKED);
        }

        final var ceremony = passkeyCeremonyStore.consume(dto.getCeremonyId())
                .filter(stored -> stored.purpose() == PasskeyCeremonyPurpose.SIGN_IN)
                .orElseThrow(() -> signInExpired(clientIp));
        final var user = verifiedUser(ceremony, dto.getCredential(), clientIp);

        if (Boolean.FALSE.equals(user.getActive())) {
            throw new DisabledException("Account is deactivated");
        }
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new EmailNotVerifiedException();
        }

        loginAttemptService.loginSucceeded(clientIp);
        passkeySignInStatsRepository.increment(analyticsDays.today());
        log.info("Passkey sign-in user id={}", user.getId());

        return signInService.signIn(user, SignInMethod.PASSKEY, Boolean.TRUE.equals(dto.getRememberMe()));
    }

    private User verifiedUser(final PasskeyCeremony ceremony, final JsonNode credential, final String clientIp) {
        try {
            return passkeyAssertions.verify(ceremony, credential, null);
        } catch (final PasskeyVerificationException | ApiException exception) {
            loginAttemptService.loginFailed(clientIp);
            throw exception;
        }
    }

    private SignInExpiredException signInExpired(final String clientIp) {
        loginAttemptService.loginFailed(clientIp);

        return new SignInExpiredException(PasskeyCeremonyStore.EXPIRED);
    }

}
