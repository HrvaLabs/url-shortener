package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.OAuth2ClientConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.listener.UserRegisteredEvent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.AuthoritiesService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.SignInMethodService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.signin.OAuthSignInCodes;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
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
    private static final String PROVIDER_TURNED_OFF = "provider_turned_off";
    private static final String PROVIDER_DISABLED = "provider_disabled";
    private static final String SIGN_IN_UNAVAILABLE = "sign_in_unavailable";

    private final UserRepository userRepository;
    private final AuthoritiesService authoritiesService;
    private final OAuthSignInCodes signInCodes;
    private final AppProperties appProperties;
    private final Validator validator;
    private final ApplicationEventPublisher eventPublisher;
    private final SignInMethodService signInMethodService;
    private final SettingsService settingsService;

    public OAuth2LoginSuccessHandler(
            final UserRepository userRepository,
            final AuthoritiesService authoritiesService,
            final OAuthSignInCodes signInCodes,
            final AppProperties appProperties,
            final Validator validator,
            final ApplicationEventPublisher eventPublisher,
            final SignInMethodService signInMethodService,
            final SettingsService settingsService) {
        this.userRepository = userRepository;
        this.authoritiesService = authoritiesService;
        this.signInCodes = signInCodes;
        this.appProperties = appProperties;
        this.validator = validator;
        this.eventPublisher = eventPublisher;
        this.signInMethodService = signInMethodService;
        this.settingsService = settingsService;
    }

    @Override
    @Transactional
    public void onAuthenticationSuccess(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final Authentication authentication) throws IOException {

        final var oAuth2User = (OAuth2User) authentication.getPrincipal();
        final var provider = providerOf(authentication);

        final var method = SignInMethod.ofProvider(provider);
        final var providerSwitch = method.flatMap(OAuthProviders::switchOf);
        if (providerSwitch.isPresent() && !settingsService.isOn(providerSwitch.get())) {
            log.info("OAuth2 login refused: this provider is turned off on hrva.cc provider={}", provider);
            redirectToCallback(response, "?error=" + PROVIDER_DISABLED + "&provider=" + method.get().name());
            return;
        }

        if (!emailVerified(oAuth2User)) {
            log.warn("OAuth2 login refused: the provider sent no verified email provider={}", provider);
            redirectToCallback(response, "?error=" + unverifiedEmailError(provider));
            return;
        }

        final var email = extractEmail(oAuth2User);

        if (email == null) {
            log.warn("OAuth2 login failed: no email returned from provider");
            redirectToCallback(response, "?error=no_email");
            return;
        }

        if (!validator.validateValue(User.class, EMAIL, email).isEmpty()) {
            log.warn("OAuth2 login failed: the provider's email is not a valid address");
            redirectToCallback(response, "?error=invalid_email");
            return;
        }

        final var existingUser = userRepository.findByEmail(email);
        if (existingUser.isEmpty() && !settingsService.isOn(SettingKey.NEW_SIGNUPS)) {
            log.warn("OAuth2 sign-up refused: registrations are disabled provider={}", provider);
            redirectToCallback(response, "?error=registration_disabled");
            return;
        }

        final var user = existingUser.orElseGet(() -> createOAuth2User(email, provider));

        if (Boolean.FALSE.equals(user.getActive())) {
            log.warn("OAuth2 login blocked: inactive account email={} user id={}", EmailAddresses.masked(email), user.getId());
            redirectToCallback(response, "?error=account_inactive");
            return;
        }

        if (existingUser.isPresent() && method.isPresent() && signInMethodService.isTurnedOff(user.getId(), method.get())) {
            log.info("OAuth2 login refused: the account turned this provider off provider={} user id={}", provider, user.getId());
            redirectToCallback(response, "?error=" + PROVIDER_TURNED_OFF + "&provider=" + method.get().name());
            return;
        }

        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            if (userRepository.confirmEmailAndReplacePassword(user.getId(), SecureTokens.random()) == 1) {
                log.info("OAuth2 login confirmed the account's email and replaced its unconfirmed password user id={}", user.getId());
            }
        }

        if (method.isEmpty()) {
            log.warn("OAuth2 sign-in method not recorded, unrecognised provider={} user id={}", provider, user.getId());
        }

        final String code;
        try {
            code = signInCodes.create(user.getId(), method.orElse(null));
        } catch (final SignInUnavailableException exception) {
            log.warn("OAuth2 login code unavailable user id={}", user.getId());
            redirectToCallback(response, "?error=" + SIGN_IN_UNAVAILABLE);
            return;
        }

        if (Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            log.info("OAuth2 login needs the second step, the code leads to it user id={}", user.getId());
            redirectToCallback(response, "?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
            return;
        }

        final var signedInAt = LocalDateTime.now();
        userRepository.recordLastLogin(user.getId(), signedInAt);
        method.ifPresent(signInMethod -> signInMethodService.record(user.getId(), signInMethod, signedInAt));

        log.info("OAuth2 login success email={} user id={}", EmailAddresses.masked(email), user.getId());
        redirectToCallback(response, "?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }

    private void redirectToCallback(final HttpServletResponse response, final String query) throws IOException {
        CallbackRedirects.send(response, appProperties.getFrontendUrl() + "/auth/callback" + query);
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
