package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.OAuth2ClientConfiguration;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class OAuthProviders {

    public static final String GOOGLE = "google";
    public static final String GITHUB = OAuth2ClientConfiguration.GITHUB;

    private final ClientRegistrationRepository clientRegistrationRepository;

    public boolean configured(final String registrationId) {
        if (!StringUtils.hasText(registrationId)) {
            return false;
        }

        final var registration = clientRegistrationRepository.findByRegistrationId(registrationId);
        return registration != null && StringUtils.hasText(registration.getClientId());
    }

    public static Optional<SettingKey> switchOf(final SignInMethod method) {
        return switch (method) {
            case GOOGLE -> Optional.of(SettingKey.GOOGLE_SIGN_IN);
            case GITHUB -> Optional.of(SettingKey.GITHUB_SIGN_IN);
            case PASSWORD, PASSKEY -> Optional.empty();
        };
    }

}
