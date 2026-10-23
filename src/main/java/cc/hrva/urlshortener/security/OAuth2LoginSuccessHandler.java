package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.AuthoritiesService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final UserRepository userRepository;
    private final AuthoritiesService authoritiesService;
    private final TokenProvider tokenProvider;
    private final AppProperties appProperties;
    private final SendingEmailService sendingEmailService;
    private final ApplicationEventPublisher eventPublisher;

    public OAuth2LoginSuccessHandler(
            final UserRepository userRepository,
            final AuthoritiesService authoritiesService,
            final TokenProvider tokenProvider,
            final AppProperties appProperties,
            final SendingEmailService sendingEmailService,
            final ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.authoritiesService = authoritiesService;
        this.tokenProvider = tokenProvider;
        this.appProperties = appProperties;
        this.sendingEmailService = sendingEmailService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public void onAuthenticationSuccess(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final Authentication authentication) throws IOException {

        final var oAuth2User = (OAuth2User) authentication.getPrincipal();
        final var email = extractEmail(oAuth2User);

        if (email == null) {
            log.warn("OAuth2 login failed: no email returned from provider");
            response.sendRedirect(appProperties.getFrontendUrl() + "/auth/callback?error=no_email");
            return;
        }

        final var user = userRepository.findByEmail(email)
                .orElseGet(() -> createOAuth2User(email, "google"));

        if (Boolean.FALSE.equals(user.getActive())) {
            log.warn("OAuth2 login blocked: inactive account email={}", email);
            response.sendRedirect(appProperties.getFrontendUrl() + "/auth/callback?error=account_inactive");
            return;
        }

        user.userLoggedIn();
        userRepository.save(user);

        final var springAuth = new UsernamePasswordAuthenticationToken(
                user.getEmail(), null,
                user.getAuthorities().stream()
                        .map(a -> new SimpleGrantedAuthority(a.getName()))
                        .toList());

        final var jwt = tokenProvider.createToken(springAuth);
        final var redirectUrl = String.format("%s/auth/callback?token=%s",
                appProperties.getFrontendUrl(),
                URLEncoder.encode(jwt, StandardCharsets.UTF_8));

        log.info("OAuth2 login success email={}", email);
        response.sendRedirect(redirectUrl);
    }

    private String extractEmail(final OAuth2User oAuth2User) {
        final var email = oAuth2User.getAttribute("email");
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
        log.info("Created OAuth2 user email={}", email);
        sendingEmailService.sendNewUserNotificationToAdmin(saved);
        eventPublisher.publishEvent(new AdminAlertEvent.UserSignedUp(saved.getId(), provider));
        return saved;
    }

}
