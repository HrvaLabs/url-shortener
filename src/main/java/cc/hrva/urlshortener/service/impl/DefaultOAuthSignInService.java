package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.OAuthSignInService;
import cc.hrva.urlshortener.service.SignInService;
import cc.hrva.urlshortener.service.signin.OAuthSignInCodes;
import cc.hrva.urlshortener.service.signin.SignInMethodGuard;
import cc.hrva.urlshortener.service.twostep.AccountLock;
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
public class DefaultOAuthSignInService implements OAuthSignInService {

    private final ClientIpResolver clientIpResolver;
    private final LoginAttemptService loginAttemptService;
    private final OAuthSignInCodes signInCodes;
    private final UserRepository userRepository;
    private final AccountLock accountLock;
    private final SignInMethodGuard signInMethodGuard;
    private final SignInService signInService;

    @Override
    @Transactional
    public ResponseEntity<JWTTokenDto> exchange(final String code, final HttpServletRequest request) {
        final var clientIp = clientIpResolver.getClientIp(request);
        loginAttemptService.checkNetwork(clientIp);

        final var signIn = signInCodes.consume(code).orElseThrow(() -> expired(clientIp));
        final var user = accountLock.lock(userRepository.findById(signIn.userId()).orElseThrow(() -> expired(clientIp)));
        if (Boolean.FALSE.equals(user.getActive())) {
            throw new DisabledException("Account is deactivated");
        }
        if (!signInMethodGuard.stillOn(user, signIn.method())) {
            log.info("OAuth sign-in code refused, the method was turned off since user id={} method={}", user.getId(), signIn.method());
            throw new SignInExpiredException();
        }
        if (Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            return signInService.requireSecondStep(user, signIn.method(), false);
        }

        log.info("OAuth sign-in code exchanged user id={}", user.getId());
        return signInService.tokenFor(user, false);
    }

    private SignInExpiredException expired(final String clientIp) {
        loginAttemptService.networkFailed(clientIp);

        return new SignInExpiredException();
    }

}
