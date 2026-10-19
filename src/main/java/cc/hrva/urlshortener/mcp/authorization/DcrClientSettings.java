package cc.hrva.urlshortener.mcp.authorization;

import java.time.Duration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.converter.OAuth2ClientRegistrationRegisteredClientConverter;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

public class DcrClientSettings implements Converter<OAuth2ClientRegistration, RegisteredClient> {

    public static final Duration ACCESS_TOKEN_TIME_TO_LIVE = Duration.ofHours(1);

    private final OAuth2ClientRegistrationRegisteredClientConverter delegate =
            new OAuth2ClientRegistrationRegisteredClientConverter();

    public DcrClientSettings() {
        delegate.setTokenSettingsCustomizer(tokenSettings -> tokenSettings.accessTokenTimeToLive(ACCESS_TOKEN_TIME_TO_LIVE));
    }

    @Override
    public RegisteredClient convert(final OAuth2ClientRegistration clientRegistration) {
        final var registeredClient = delegate.convert(clientRegistration);
        final var clientSettings = ClientSettings.withSettings(registeredClient.getClientSettings().getSettings())
                .requireAuthorizationConsent(false)
                .build();

        return RegisteredClient.from(registeredClient)
                .clientSettings(clientSettings)
                .build();
    }

}
