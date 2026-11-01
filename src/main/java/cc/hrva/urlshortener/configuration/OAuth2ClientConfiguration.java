package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.configuration.properties.GitHubSignInProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({OAuth2ClientProperties.class, GitHubSignInProperties.class})
public class OAuth2ClientConfiguration {

    public static final String GITHUB = "github";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

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

    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient() {
        final var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        final var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenResponseClient.setRestClient(RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        return tokenResponseClient;
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
