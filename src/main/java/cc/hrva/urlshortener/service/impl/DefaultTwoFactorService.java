package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.TwoFactorEnableResponse;
import cc.hrva.urlshortener.dto.TwoFactorSetupResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.TwoFactorRecoveryCode;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.Totp;
import cc.hrva.urlshortener.service.TwoFactorService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import cc.hrva.urlshortener.service.twostep.RecoveryCodes;
import cc.hrva.urlshortener.service.twostep.TwoStepCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultTwoFactorService implements TwoFactorService {

    private final UserService userService;
    private final UserRepository userRepository;
    private final AppProperties appProperties;
    private final PasswordEncoder passwordEncoder;
    private final TwoFactorRecoveryCodeRepository recoveryCodeRepository;
    private final RecoveryCodes recoveryCodes;
    private final AccountLock accountLock;

    @Override
    @Transactional
    public TwoFactorSetupResponse setup() {
        final var user = accountLock.lock(currentUser());
        if (Boolean.TRUE.equals(user.getTwoFactorEnabled())) {
            throw new ApiException("An authenticator app is already set up. Remove it first.");
        }

        final var secret = Totp.generateSecret();
        user.setTwoFactorSecret(secret);
        user.setTwoFactorEnabled(false);
        userRepository.save(user);

        log.info("2FA setup initiated for user id={}", user.getId());
        return new TwoFactorSetupResponse(secret, Totp.buildOtpAuthUri(secret, user.getEmail(), appProperties.getAppName()));
    }

    @Override
    @Transactional
    public TwoFactorEnableResponse enable(final String code) {
        final var user = accountLock.lock(currentUser());

        if (Boolean.TRUE.equals(user.getTwoFactorEnabled())) {
            throw new ApiException("Two-factor authentication is already enabled");
        }
        if (user.getTwoFactorSecret() == null) {
            throw new ApiException("Start two-factor setup first");
        }
        if (!Totp.verify(user.getTwoFactorSecret(), TwoStepCodes.normalized(code))) {
            throw new ApiException("Invalid verification code");
        }

        user.setTwoFactorEnabled(true);
        user.setTwoStepEnabled(true);
        userRepository.save(user);

        log.info("2FA enabled for user id={}", user.getId());
        return new TwoFactorEnableResponse(recoveryCodes.issueIfNone(user));
    }

    @Override
    @Transactional
    public boolean verifyLoginCode(final User user, final String typedCode) {
        if (typedCode == null) {
            return false;
        }

        final var code = TwoStepCodes.normalized(typedCode);
        if (Boolean.TRUE.equals(user.getTwoFactorEnabled()) && user.getTwoFactorSecret() != null
                && Totp.verify(user.getTwoFactorSecret(), code)) {
            return true;
        }
        for (final TwoFactorRecoveryCode recoveryCode : recoveryCodeRepository.findByUserAndUsedFalse(user)) {
            if (passwordEncoder.matches(code, recoveryCode.getCodeHash())) {
                recoveryCode.setUsed(true);
                recoveryCodeRepository.save(recoveryCode);
                log.info("2FA recovery code consumed for user id={}", user.getId());
                return true;
            }
        }
        return false;
    }

    private User currentUser() {
        final var user = userService.getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException("Not authenticated");
        }
        return user;
    }

}
