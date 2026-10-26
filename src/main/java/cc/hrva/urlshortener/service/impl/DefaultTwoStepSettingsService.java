package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.TwoFactorCodeDto;
import cc.hrva.urlshortener.dto.TwoFactorEnableResponse;
import cc.hrva.urlshortener.dto.TwoStepDisableDto;
import cc.hrva.urlshortener.dto.TwoStepStatusResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.TwoFactorService;
import cc.hrva.urlshortener.service.TwoStepSettingsService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.passkey.PasskeyAssertions;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonies;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyStore;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import cc.hrva.urlshortener.service.twostep.RecoveryCodes;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultTwoStepSettingsService implements TwoStepSettingsService {

    private static final String NOT_AUTHENTICATED = "Not authenticated";
    private static final String ADD_A_PASSKEY_FIRST = "Add a passkey or security key first.";
    private static final String ALREADY_ON = "Two-step verification is already on.";
    private static final String OFF = "Two-step verification is off.";
    private static final String NO_PASSKEYS = "This account has no passkeys or security keys.";
    private static final String ONE_FACTOR = "Confirm with a code, a recovery code or a passkey.";
    private static final String INVALID_CODE = "Invalid verification code";
    private static final String NO_AUTHENTICATOR_APP = "No authenticator app is set up.";
    private static final String LAST_METHOD =
            "Your authenticator app is your only two-step method. Turn off two-step verification, or add a passkey or security key first.";

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final PasskeyCeremonies passkeyCeremonies;
    private final PasskeyCeremonyStore passkeyCeremonyStore;
    private final PasskeyOptionsFactory passkeyOptionsFactory;
    private final PasskeyAssertions passkeyAssertions;
    private final TwoFactorService twoFactorService;
    private final RecoveryCodes recoveryCodes;
    private final LoginAttemptService loginAttemptService;
    private final AccountLock accountLock;

    @Override
    public TwoStepStatusResponse status() {
        final var user = currentUser();

        return new TwoStepStatusResponse(Boolean.TRUE.equals(user.getTwoStepEnabled()), Boolean.TRUE.equals(user.getTwoFactorEnabled()),
                passkeyCredentialRepository.countByUser(user), recoveryCodes.remaining(user));
    }

    @Override
    @Transactional
    public TwoFactorEnableResponse enableWithPasskeys() {
        final var user = accountLock.lock(currentUser());
        if (passkeyCredentialRepository.countByUser(user) == 0) {
            throw new ApiException(ADD_A_PASSKEY_FIRST);
        }
        if (Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            throw new ApiException(ALREADY_ON);
        }

        user.setTwoStepEnabled(true);
        userRepository.save(user);
        log.info("Two-step verification turned on with passkeys user id={}", user.getId());

        return new TwoFactorEnableResponse(recoveryCodes.issueIfNone(user));
    }

    @Override
    public PasskeyOptionsResponse disableOptions() {
        final var user = currentUser();
        requireTwoStepOn(user);
        final var passkeys = passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user);
        if (passkeys.isEmpty()) {
            throw new ApiException(NO_PASSKEYS);
        }

        return passkeyCeremonies.startRequest(PasskeyCeremonyPurpose.TWO_STEP_DISABLE, passkeyOptionsFactory.descriptors(passkeys),
                user.getId());
    }

    @Override
    @Transactional
    public void disable(final TwoStepDisableDto dto) {
        final var user = accountLock.lock(currentUser());
        requireTwoStepOn(user);
        final var withCode = confirmsWithCode(dto);
        requireNotLocked(user);

        if (withCode) {
            verifyCode(user, dto.getCode());
        } else {
            verifyPasskey(user, dto);
        }
        loginAttemptService.loginSucceeded(twoFactorAttemptsKey(user));

        user.setTwoStepEnabled(false);
        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        userRepository.save(user);
        recoveryCodes.deleteAll(user);
        log.info("Two-step verification turned off user id={}", user.getId());
    }

    @Override
    @Transactional
    public void removeTotp(final TwoFactorCodeDto dto) {
        final var user = accountLock.lock(currentUser());
        if (!Boolean.TRUE.equals(user.getTwoFactorEnabled())) {
            throw new ApiException(NO_AUTHENTICATOR_APP);
        }
        if (passkeyCredentialRepository.countByUser(user) == 0) {
            throw new ApiException(LAST_METHOD);
        }
        requireNotLocked(user);

        verifyCode(user, dto.getCode());
        loginAttemptService.loginSucceeded(twoFactorAttemptsKey(user));

        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        userRepository.save(user);
        log.info("Authenticator app removed user id={}", user.getId());
    }

    private void verifyCode(final User user, final String code) {
        if (!twoFactorService.verifyLoginCode(user, code)) {
            loginAttemptService.loginFailed(twoFactorAttemptsKey(user));
            log.info("Two-step settings code refused user id={}", user.getId());
            throw new ApiException(INVALID_CODE);
        }
    }

    private void verifyPasskey(final User user, final TwoStepDisableDto dto) {
        final var ceremony = passkeyCeremonyStore.consume(dto.getCeremonyId())
                .filter(stored -> stored.purpose() == PasskeyCeremonyPurpose.TWO_STEP_DISABLE)
                .filter(stored -> Objects.equals(stored.userId(), user.getId()))
                .orElseThrow(() -> new SignInExpiredException(PasskeyCeremonyStore.EXPIRED));
        try {
            passkeyAssertions.verify(ceremony, dto.getCredential(), user);
        } catch (final PasskeyVerificationException | ApiException exception) {
            loginAttemptService.loginFailed(twoFactorAttemptsKey(user));
            log.info("Two-step settings passkey refused user id={}", user.getId());
            throw exception;
        }
    }

    private void requireNotLocked(final User user) {
        if (loginAttemptService.isBlocked(twoFactorAttemptsKey(user))) {
            throw new NoAuthorizationException(LoginAttemptService.TWO_FACTOR_BLOCKED);
        }
    }

    private User currentUser() {
        final var user = userService.getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException(NOT_AUTHENTICATED);
        }

        return user;
    }

    private static void requireTwoStepOn(final User user) {
        if (!Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            throw new ApiException(OFF);
        }
    }

    private static boolean confirmsWithCode(final TwoStepDisableDto dto) {
        final var code = StringUtils.isNotBlank(dto.getCode());
        final var ceremony = StringUtils.isNotBlank(dto.getCeremonyId());
        final var credential = dto.getCredential() != null && !dto.getCredential().isNull();
        if (code && !ceremony && !credential) {
            return true;
        }
        if (!code && ceremony && credential) {
            return false;
        }

        throw new ApiException(ONE_FACTOR);
    }

    private static String twoFactorAttemptsKey(final User user) {
        return LoginAttemptService.TWO_FACTOR_PREFIX + user.getId();
    }

}
