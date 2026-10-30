package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.OAuth2ClientConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.listener.UserRegisteredEvent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.AuthoritiesService;
import cc.hrva.urlshortener.service.SignInMethodService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
import cc.hrva.urlshortener.service.twostep.TwoStepMethods;
import cc.hrva.urlshortener.util.EmailAddresses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Validator;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);
    private static final String DEFAULT_PROVIDER = "google";
    private static final String EMAIL = "email";
    private static final String GITHUB_EMAIL_UNVERIFIED = "github_email_unverified";
    private static final String GOOGLE_EMAIL_UNVERIFIED = "google_email_unverified";

    private final UserRepository userRepository;
    private final AuthoritiesService authoritiesService;
    private final TokenProvider tokenProvider;
    private final AppProperties appProperties;
    private final Validator validator;
    private final ApplicationEventPublisher eventPublisher;
    private final PendingLoginStore pendingLoginStore;
    private final TwoStepMethods twoStepMethods;
    private final SignInMethodService signInMethodService;

    public OAuth2LoginSuccessHandler(
            final UserRepository userRepository,
            final AuthoritiesService authoritiesService,
            final TokenProvider tokenProvider,
            final AppProperties appProperties,
            final Validator validator,
            final ApplicationEventPublisher eventPublisher,
            final PendingLoginStore pendingLoginStore,
            final TwoStepMethods twoStepMethods,
            final SignInMethodService signInMethodService) {
        this.userRepository = userRepository;
        this.authoritiesService = authoritiesService;
        this.tokenProvider = tokenProvider;
        this.appProperties = appProperties;
        this.validator = validator;
        this.eventPublisher = eventPublisher;
        this.pendingLoginStore = pendingLoginStore;
        this.twoStepMethods = twoStepMethods;
        this.signInMethodService = signInMethodService;
    }

    @Override
    @Transactional
    public void onAuthenticationSuccess(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final Authentication authentication) throws IOException {

        final var oAuth2User = (OAuth2User) authentication.getPrincipal();
        final var provider = providerOf(authentication);

        if (!emailVerified(oAuth2User)) {
            log.warn("OAuth2 login refused: the provider sent no verified email provider={}", provider);
            response.sendRedirect(appProperties.getFrontendUrl() + "/auth/callback?error=" + unverifiedEmailError(provider));
            return;
        }

        final var email = extractEmail(oAuth2User);

        if (email == null) {
            log.warn("OAuth2 login failed: no email returned from provider");
            response.sendRedirect(appProperties.getFrontendUrl() + "/auth/callback?error=no_email");
            return;
        }

        if (!validator.validateValue(User.class, EMAIL, email).isEmpty()) {
            log.warn("OAuth2 login failed: the provider's email is not a valid address");
            response.sendRedirect(appProperties.getFrontendUrl() + "/auth/callback?error=invalid_email");
            return;
        }

        final var existingUser = userRepository.findByEmail(email);
        if (existingUser.isEmpty() && !appProperties.isRegistrationEnabled()) {
            log.warn("OAuth2 sign-up refused: registrations are disabled provider={}", provider);
            response.sendRedirect(appProperties.getFrontendUrl() + "/auth/callback?error=registration_disabled");
            return;
        }

        final var user = existingUser.orElseGet(() -> createOAuth2User(email, provider));

        if (Boolean.FALSE.equals(user.getActive())) {
            log.warn("OAuth2 login blocked: inactive account email={} user id={}", EmailAddresses.masked(email), user.getId());
            response.sendRedirect(appProperties.getFrontendUrl() + "/auth/callback?error=account_inactive");
            return;
        }

        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            if (userRepository.confirmEmailAndReplacePassword(user.getId(), SecureTokens.random()) == 1) {
                log.info("OAuth2 login confirmed the account's email and replaced its unconfirmed password user id={}", user.getId());
            }
        }

        final var method = SignInMethod.ofProvider(provider);
        if (method.isEmpty()) {
            log.warn("OAuth2 sign-in method not recorded, unrecognised provider={} user id={}", provider, user.getId());
        }

        if (Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            redirectToSecondStep(response, user, method.orElse(null));
            return;
        }

        final var signedInAt = LocalDateTime.now();
        userRepository.recordLastLogin(user.getId(), signedInAt);
        method.ifPresent(signInMethod -> signInMethodService.record(user.getId(), signInMethod, signedInAt));

        final var springAuth = new UsernamePasswordAuthenticationToken(
                user.getEmail(), null,
                user.getAuthorities().stream()
                        .map(a -> new SimpleGrantedAuthority(a.getName()))
                        .toList());

        final var jwt = tokenProvider.createToken(springAuth);
        final var redirectUrl = String.format("%s/auth/callback?token=%s",
                appProperties.getFrontendUrl(),
                URLEncoder.encode(jwt, StandardCharsets.UTF_8));

        log.info("OAuth2 login success email={} user id={}", EmailAddresses.masked(email), user.getId());
        response.sendRedirect(redirectUrl);
    }

    private void redirectToSecondStep(final HttpServletResponse response, final User user, final SignInMethod method) throws IOException {
        try {
            final var methods = twoStepMethods.of(user).stream()
                    .map(TwoStepMethod::name)
                    .collect(Collectors.joining(","));
            final var pendingToken = pendingLoginStore.create(user.getId(), false, method);
            final var redirectUrl = String.format("%s/auth/callback?pendingToken=%s&methods=%s",
                    appProperties.getFrontendUrl(),
                    URLEncoder.encode(pendingToken, StandardCharsets.UTF_8),
                    methods);

            log.info("OAuth2 login needs the second step user id={}", user.getId());
            response.sendRedirect(redirectUrl);
        } catch (final SignInUnavailableException exception) {
            log.warn("OAuth2 login second step unavailable user id={}", user.getId());
            response.sendRedirect(appProperties.getFrontendUrl() + "/auth/callback?error=two_step_unavailable");
        }
    }

    private static String providerOf(final Authentication authentication) {
        return authentication instanceof OAuth2AuthenticationToken token ? token.getAuthorizedClientRegistrationId() : DEFAULT_PROVIDER;
    }

    private static boolean emailVerified(final OAuth2User oAuth2User) {
        return Boolean.parseBoolean(String.valueOf(oAuth2User.<Object>getAttribute(OAuth2LoginUserService.EMAIL_VERIFIED)));
    }

    private static String unverifiedEmailError(final String provider) {
        return OAuth2ClientConfiguration.GITHUB.equals(provider) ? GITHUB_EMAIL_UNVERIFIED : GOOGLE_EMAIL_UNVERIFIED;
    }

    private String extractEmail(final OAuth2User oAuth2User) {
        final var email = oAuth2User.getAttribute(EMAIL);
        return email instanceof String s ? s : null;
    }

    private User createOAuth2User(final String email, final String provider) {
        final var newUser = User.builder()
                .email(email)
                .password(UUID.randomUUID().toString())
                .authProvider(provider)
                .authorities(List.of(authoritiesService.getDefaultAuthority()))
                .apiKeySlots(appProperties.getUserApiKeySlots())
                .active(true)
                .emailVerified(true)
                .build();

        final var saved = userRepository.save(newUser);
        log.info("Created OAuth2 user email={} user id={}", EmailAddresses.masked(email), saved.getId());
        eventPublisher.publishEvent(new UserRegisteredEvent(saved, null));
        eventPublisher.publishEvent(new AdminAlertEvent.UserSignedUp(saved.getId(), provider));
        return saved;
    }

}
