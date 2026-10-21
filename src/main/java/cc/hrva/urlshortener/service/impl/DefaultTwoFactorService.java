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
import java.security.SecureRandom;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultTwoFactorService implements TwoFactorService {

    private static final int RECOVERY_CODE_COUNT = 10;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserService userService;
    private final UserRepository userRepository;
    private final AppProperties appProperties;
    private final PasswordEncoder passwordEncoder;
    private final TwoFactorRecoveryCodeRepository recoveryCodeRepository;

    @Override
    @Transactional
    public TwoFactorSetupResponse setup() {
        final var user = currentUser();
        requireLocalAccount(user);
        if (Boolean.TRUE.equals(user.getTwoFactorEnabled())) {
            throw new ApiException("Two-factor authentication is already enabled. Disable it first.");
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
        final var user = currentUser();
        requireLocalAccount(user);

        if (Boolean.TRUE.equals(user.getTwoFactorEnabled())) {
            throw new ApiException("Two-factor authentication is already enabled");
        }
        if (user.getTwoFactorSecret() == null) {
            throw new ApiException("Start two-factor setup first");
        }
        if (!Totp.verify(user.getTwoFactorSecret(), code)) {
            throw new ApiException("Invalid verification code");
        }

        user.setTwoFactorEnabled(true);
        userRepository.save(user);

        recoveryCodeRepository.deleteByUser(user);
        final var plainCodes = generateRecoveryCodes();
        recoveryCodeRepository.saveAll(plainCodes.stream()
                .map(plain -> TwoFactorRecoveryCode.builder()
                        .user(user)
                        .codeHash(passwordEncoder.encode(plain))
                        .used(false)
                        .build())
                .toList());

        log.info("2FA enabled for user id={}", user.getId());
        return new TwoFactorEnableResponse(plainCodes);
    }

    @Override
    @Transactional
    public void disable(final String code) {
        final var user = currentUser();

        if (!Boolean.TRUE.equals(user.getTwoFactorEnabled())) {
            throw new ApiException("Two-factor authentication is not enabled");
        }
        if (!verifyLoginCode(user, code)) {
            throw new ApiException("Invalid verification code");
        }

        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        userRepository.save(user);
        recoveryCodeRepository.deleteByUser(user);

        log.info("2FA disabled for user id={}", user.getId());
    }

    @Override
    @Transactional
    public boolean verifyLoginCode(final User user, final String code) {
        if (code == null) {
            return false;
        }
        if (Totp.verify(user.getTwoFactorSecret(), code)) {
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

    private List<String> generateRecoveryCodes() {
        return IntStream.range(0, RECOVERY_CODE_COUNT)
                .mapToObj(i -> randomGroup() + "-" + randomGroup())
                .toList();
    }

    private String randomGroup() {
        final var sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        }
        return sb.toString();
    }

    private void requireLocalAccount(final User user) {
        final var provider = user.getAuthProvider();
        if (provider != null && !"local".equals(provider)) {
            throw new ApiException("Two-factor authentication is only available for password accounts");
        }
    }

    private User currentUser() {
        final var user = userService.getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException("Not authenticated");
        }
        return user;
    }

}
