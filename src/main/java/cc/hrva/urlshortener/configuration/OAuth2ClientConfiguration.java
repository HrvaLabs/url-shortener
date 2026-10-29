package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.configuration.properties.GitHubSignInProperties;
import java.util.ArrayList;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties({OAuth2ClientProperties.class, GitHubSignInProperties.class})
public class OAuth2ClientConfiguration {

    public static final String GITHUB = "github";

    @Bean
    public InMemoryClientRegistrationRepository clientRegistrationRepository(
            final OAuth2ClientProperties oAuth2ClientProperties,
            final GitHubSignInProperties gitHubSignInProperties) {
        final var registrations = new ArrayList<>(new OAuth2ClientPropertiesMapper(oAuth2ClientProperties).asClientRegistrations().values());
        if (StringUtils.hasText(gitHubSignInProperties.getClientId())) {
            registrations.add(gitHub(gitHubSignInProperties));
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }

    private static ClientRegistration gitHub(final GitHubSignInProperties properties) {
        return CommonOAuth2Provider.GITHUB.getBuilder(GITHUB)
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .scope(properties.getScope())
                .redirectUri(properties.getRedirectUri())
                .build();
    }

}
