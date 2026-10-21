package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.converter.UserRegisterDtoToUserConverter;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.EmailNotVerifiedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.TwoFactorService;
import cc.hrva.urlshortener.service.VerificationTokenService;
import cc.hrva.urlshortener.validator.UserValidator;
import org.apache.commons.lang3.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.LoginDto;
import cc.hrva.urlshortener.dto.UserRegisterDto;
import cc.hrva.urlshortener.service.AuthService;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultAuthService implements AuthService {

    private static final String TWO_FACTOR_ATTEMPTS_PREFIX = "2fa:";

    private final UserService userService;
    private final UserValidator userValidator;
    private final TokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final ClientIpResolver clientIpResolver;
    private final LoginAttemptService loginAttemptService;
    private final SendingEmailService sendingEmailService;
    private final TwoFactorService twoFactorService;
    private final UserToUserDtoConverter userToUserDtoConverter;
    private final VerificationTokenService verificationTokenService;
    private final AuthenticationManagerBuilder authenticationManagerBuilder;
    private final UserRegisterDtoToUserConverter userRegisterDtoToUserConverter;
    private final AppProperties appProperties;

    @Override
    public String register(final UserRegisterDto userRegisterDto) {
        log.info("User registered email={}", userRegisterDto.getEmail());

        userValidator.checkRegistrationEnabled();
        userValidator.checkEmailUniqueness(userRegisterDto.getEmail());

        final var user = userRegisterDtoToUserConverter.convert(userRegisterDto);
        return userService.register(user).getEmail();
    }

    @Override
    @Transactional
    public ResponseEntity<JWTTokenDto> login(final LoginDto loginDto, final HttpServletRequest request) {
        log.info("User logged in email={}", loginDto.getEmail());

        final var clientIp = clientIpResolver.getClientIp(request);
        if (loginAttemptService.isBlocked(clientIp)) {
            throw new NoAuthorizationException("Request has been blocked");
        }
        final var token = getToken(loginDto);
        final var httpHeaders = getHttpHeaders(token);
        final var user = userService.fetchUserFromEmail(loginDto.getEmail());

        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new EmailNotVerifiedException("Please verify your email before logging in.");
        }

        if (Boolean.TRUE.equals(user.getTwoFactorEnabled())) {
            if (StringUtils.isBlank(loginDto.getCode())) {
                return new ResponseEntity<>(JWTTokenDto.twoFactorRequired(), HttpStatus.OK);
            }
            final var twoFactorAttemptsKey = TWO_FACTOR_ATTEMPTS_PREFIX + user.getId();
            if (loginAttemptService.isBlocked(twoFactorAttemptsKey)) {
                throw new NoAuthorizationException("Too many invalid two-factor codes. Try again later.");
            }
            if (!twoFactorService.verifyLoginCode(user, loginDto.getCode())) {
                loginAttemptService.loginFailed(twoFactorAttemptsKey);
                loginAttemptService.loginFailed(clientIp);
                throw new NoAuthorizationException("Invalid two-factor code");
            }
            loginAttemptService.loginSucceeded(twoFactorAttemptsKey);
        }

        userService.userHasLoggedIn(user);
        final var jwtTokenDto = JWTTokenDto.builder()
                .token(token)
                .user(userToUserDtoConverter.convert(user))
                .build();

        return new ResponseEntity<>(jwtTokenDto, httpHeaders, HttpStatus.OK);
    }

    @Override
    @Transactional
    public void verifyEmail(final String token) {
        final var verificationToken = verificationTokenService.validateToken(token);
        final var user = verificationToken.getUser();
        user.setEmailVerified(true);
        userService.persistUser(user);
        verificationTokenService.deactivateAndSaveToken(verificationToken);
        sendingEmailService.sendWelcomeEmail(user);
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

    private String getToken(final LoginDto loginDto) {
        final var authenticationToken = new UsernamePasswordAuthenticationToken(
                loginDto.getEmail(),
                loginDto.getPassword()
        );

        final var authentication = authenticationManagerBuilder.getObject().authenticate(authenticationToken);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        final var ttl = Boolean.TRUE.equals(loginDto.getRememberMe())
                ? 2_592_000L  // 30 days
                : appProperties.getJwtTokenValiditySeconds();

        return "Bearer ".concat(tokenProvider.createToken(authentication, ttl));
    }

    private HttpHeaders getHttpHeaders(final String token) {
        final var httpHeaders = new HttpHeaders();
        httpHeaders.add(JwtFilter.AUTHORIZATION_HEADER, token);

        return httpHeaders;
    }

}
