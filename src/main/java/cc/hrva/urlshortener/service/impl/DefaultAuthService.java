package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.converter.UserRegisterDtoToUserConverter;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.SignInService;
import cc.hrva.urlshortener.service.TwoFactorService;
import cc.hrva.urlshortener.service.VerificationTokenService;
import cc.hrva.urlshortener.service.ratelimit.AccountEmailLimits;
import cc.hrva.urlshortener.service.twostep.TwoStepLockMessage;
import cc.hrva.urlshortener.util.EmailAddresses;
import cc.hrva.urlshortener.validator.UserValidator;
import org.apache.commons.lang3.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.LoginDto;
import cc.hrva.urlshortener.dto.RegistrationResponse;
import cc.hrva.urlshortener.dto.UserRegisterDto;
import cc.hrva.urlshortener.service.AuthService;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final AccountEmailLimits accountEmailLimits;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public RegistrationResponse register(final UserRegisterDto userRegisterDto) {
        log.info("Registration requested email={}", EmailAddresses.masked(userRegisterDto.getEmail()));

        userValidator.checkRegistrationEnabled();
        userRepository.findByEmail(userRegisterDto.getEmail()).ifPresentOrElse(
                existing -> signUpAttempted(existing, userRegisterDto.getPassword()),
                () -> userService.register(userRegisterDtoToUserConverter.convert(userRegisterDto)));

        return RegistrationResponse.of(userRegisterDto.getEmail());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegistrationResponse> registeredMeanwhile(final String email) {
        final var registered = userRepository.findByEmail(email);
        registered.ifPresent(user -> log.info("Sign-up lost the race for a new address to another sign-up, answering like any other user id={}", user.getId()));
        return registered.map(user -> RegistrationResponse.of(email));
    }

    private void signUpAttempted(final User existing, final String password) {
        passwordEncoder.encode(password);
        if (Boolean.FALSE.equals(existing.getActive())) {
            log.info("Sign-up with the address of a deactivated account, nothing sent user id={}", existing.getId());
            return;
        }
        if (!accountEmailLimits.allowsEmailTo(existing.getEmail())) {
            log.info("Sign-up with the address of an existing account, the address had its account emails for the hour user id={}", existing.getId());
            return;
        }
        if (Boolean.TRUE.equals(existing.getEmailVerified())) {
            log.info("Sign-up with the address of an existing account, sending a note user id={}", existing.getId());
            sendingEmailService.sendSignUpAttemptEmail(existing);
            return;
        }
        log.info("Sign-up with the address of an unverified account, sending a new verification link user id={}", existing.getId());
        verificationTokenService.deactivateActiveTokensForUser(existing);
        sendingEmailService.sendVerificationEmail(existing, verificationTokenService.createTokenForUser(existing));
    }

    @Override
    @Transactional
    @SuppressWarnings({"deprecation", "removal"})
    public ResponseEntity<JWTTokenDto> login(final LoginDto loginDto, final HttpServletRequest request) {
        log.info("User logged in email={}", EmailAddresses.masked(loginDto.getEmail()));

        final var clientIp = clientIpResolver.getClientIp(request);
        loginAttemptService.checkSignIn(clientIp, loginDto.getEmail());
        checkPassword(loginDto);
        final var user = userService.fetchUserFromEmail(loginDto.getEmail());

        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new EmailNotVerifiedException();
        }

        final var rememberMe = Boolean.TRUE.equals(loginDto.getRememberMe());
        if (Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            if (StringUtils.isBlank(loginDto.getCode())) {
                return signInService.requireSecondStep(user, SignInMethod.PASSWORD, rememberMe);
            }
            verifyLegacyCode(user, loginDto.getCode(), clientIp);
        }

        return signInService.signIn(user, SignInMethod.PASSWORD, rememberMe);
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
        log.info("Verification resend requested email={}", EmailAddresses.masked(email));
        userRepository.findByEmail(email).ifPresent(user -> {
            if (Boolean.TRUE.equals(user.getEmailVerified())) {
                return;
            }
            if (!accountEmailLimits.allowsEmailTo(user.getEmail())) {
                log.info("Verification email not sent, the address had its account emails for the hour user id={}", user.getId());
                return;
            }
            verificationTokenService.deactivateActiveTokensForUser(user);
            sendingEmailService.sendVerificationEmail(user, verificationTokenService.createTokenForUser(user));
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
            loginAttemptService.networkFailed(clientIp);
            throw new NoAuthorizationException("Invalid two-factor code");
        }
        loginAttemptService.loginSucceeded(twoFactorAttemptsKey);
    }

}
