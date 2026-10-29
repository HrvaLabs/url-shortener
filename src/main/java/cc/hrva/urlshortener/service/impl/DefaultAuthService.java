package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.converter.UserRegisterDtoToUserConverter;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.SignInService;
import cc.hrva.urlshortener.service.TwoFactorService;
import cc.hrva.urlshortener.service.VerificationTokenService;
import cc.hrva.urlshortener.service.twostep.TwoStepLockMessage;
import cc.hrva.urlshortener.util.EmailAddresses;
import cc.hrva.urlshortener.validator.UserValidator;
import org.apache.commons.lang3.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.LoginDto;
import cc.hrva.urlshortener.dto.UserRegisterDto;
import cc.hrva.urlshortener.service.AuthService;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultAuthService implements AuthService {

    private final UserService userService;
    private final UserValidator userValidator;
    private final SignInService signInService;
    private final UserRepository userRepository;
    private final ClientIpResolver clientIpResolver;
    private final LoginAttemptService loginAttemptService;
    private final SendingEmailService sendingEmailService;
    private final TwoFactorService twoFactorService;
    private final VerificationTokenService verificationTokenService;
    private final AuthenticationManagerBuilder authenticationManagerBuilder;
    private final UserRegisterDtoToUserConverter userRegisterDtoToUserConverter;
    private final TwoStepLockMessage twoStepLockMessage;

    @Override
    @Transactional
    public String register(final UserRegisterDto userRegisterDto) {
        log.info("Registration requested email={}", userRegisterDto.getEmail());

        userValidator.checkRegistrationEnabled();
        userValidator.checkEmailUniqueness(userRegisterDto.getEmail());

        final var user = userRegisterDtoToUserConverter.convert(userRegisterDto);
        return userService.register(user).getEmail();
    }

    @Override
    @Transactional
    @SuppressWarnings({"deprecation", "removal"})
    public ResponseEntity<JWTTokenDto> login(final LoginDto loginDto, final HttpServletRequest request) {
        log.info("User logged in email={}", EmailAddresses.masked(loginDto.getEmail()));

        final var clientIp = clientIpResolver.getClientIp(request);
        if (loginAttemptService.isBlocked(clientIp)) {
            throw new NoAuthorizationException(LoginAttemptService.NETWORK_BLOCKED);
        }
        checkPassword(loginDto);
        final var user = userService.fetchUserFromEmail(loginDto.getEmail());

        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new EmailNotVerifiedException();
        }

        final var rememberMe = Boolean.TRUE.equals(loginDto.getRememberMe());
        if (Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            if (StringUtils.isBlank(loginDto.getCode())) {
                return signInService.requireSecondStep(user, rememberMe);
            }
            verifyLegacyCode(user, loginDto.getCode(), clientIp);
        }

        return signInService.signIn(user, rememberMe);
    }

    @Override
    @Transactional
    public void verifyEmail(final String token) {
        final var verificationToken = verificationTokenService.validateToken(token);
        final var user = verificationToken.getUser();
        user.setEmailVerified(true);
        userService.persistUser(user);
        verificationTokenService.deactivateAndSaveToken(verificationToken);
        log.info("Email verified for user id={}", user.getId());
    }

    @Override
    @Transactional
    public void resendVerificationEmail(final String email) {
        log.info("Verification resend requested email={}", email);
        userRepository.findByEmail(email).ifPresent(user -> {
            if (!Boolean.TRUE.equals(user.getEmailVerified())) {
                verificationTokenService.deactivateActiveTokensForUser(user);
                final var token = verificationTokenService.createTokenForUser(user);
                sendingEmailService.sendVerificationEmail(user, token);
            }
        });
    }

    private void checkPassword(final LoginDto loginDto) {
        final var authenticationToken = new UsernamePasswordAuthenticationToken(
                loginDto.getEmail(),
                loginDto.getPassword()
        );

        authenticationManagerBuilder.getObject().authenticate(authenticationToken);
    }

    private void verifyLegacyCode(final User user, final String code, final String clientIp) {
        log.info("Legacy two-factor login user id={}", user.getId());

        final var twoFactorAttemptsKey = LoginAttemptService.TWO_FACTOR_PREFIX + user.getId();
        if (loginAttemptService.isBlocked(twoFactorAttemptsKey)) {
            throw new NoAuthorizationException(twoStepLockMessage.of(user));
        }
        if (!twoFactorService.verifyLoginCode(user, code)) {
            loginAttemptService.loginFailed(twoFactorAttemptsKey);
            loginAttemptService.loginFailed(clientIp);
            throw new NoAuthorizationException("Invalid two-factor code");
        }
        loginAttemptService.loginSucceeded(twoFactorAttemptsKey);
    }

}
