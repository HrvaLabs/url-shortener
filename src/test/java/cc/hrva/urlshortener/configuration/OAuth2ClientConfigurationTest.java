package cc.hrva.urlshortener.configuration;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2ClientConfigurationTest {

    private static final String GOOGLE_CLIENT_ID = "OAUTH2_GOOGLE_CLIENT_ID=test-google-client";
    private static final String GOOGLE_CLIENT_SECRET = "OAUTH2_GOOGLE_CLIENT_SECRET=test-google-secret";
    private static final String GITHUB_CLIENT_ID = "OAUTH2_GITHUB_CLIENT_ID=test-github-client";
    private static final String GITHUB_CLIENT_SECRET = "OAUTH2_GITHUB_CLIENT_SECRET=test-github-secret";

    private final ApplicationContextRunner springBootOnly = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class));

    private final ApplicationContextRunner contextRunner = springBootOnly
            .withUserConfiguration(OAuth2ClientConfiguration.class);

    @Test
    void withoutAGitHubClientIdTheAppStartsWithOnlyGoogle() {
        contextRunner.withPropertyValues(GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, "OAUTH2_GITHUB_CLIENT_ID=", "OAUTH2_GITHUB_CLIENT_SECRET=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var registrations = context.getBean(ClientRegistrationRepository.class);
                    assertThat(registrations.findByRegistrationId("google")).isNotNull();
                    assertThat(registrations.findByRegistrationId("github")).isNull();
                });
    }

    @Test
    void aBlankGitHubClientIdRegistersNoGitHub() {
        contextRunner.withPropertyValues(GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, "OAUTH2_GITHUB_CLIENT_ID=  ", GITHUB_CLIENT_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ClientRegistrationRepository.class).findByRegistrationId("github")).isNull();
                });
    }

    @Test
    void aGitHubClientIdRegistersGitHubWithOnlyTheEmailScopeAndTheHrvaCallback() {
        contextRunner.withPropertyValues(GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var gitHub = context.getBean(ClientRegistrationRepository.class).findByRegistrationId("github");
                    assertThat(gitHub).isNotNull();
                    assertThat(gitHub.getClientId()).isEqualTo("test-github-client");
                    assertThat(gitHub.getClientSecret()).isEqualTo("test-github-secret");
                    assertThat(gitHub.getScopes()).isEqualTo(Set.of("user:email"));
                    assertThat(gitHub.getRedirectUri()).isEqualTo("https://hrva.cc/login/oauth2/code/github");
                    assertThat(gitHub.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
                    assertThat(gitHub.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
                    assertThat(gitHub.getProviderDetails().getAuthorizationUri()).isEqualTo("https://github.com/login/oauth/authorize");
                    assertThat(gitHub.getProviderDetails().getTokenUri()).isEqualTo("https://github.com/login/oauth/access_token");
                    assertThat(gitHub.getProviderDetails().getUserInfoEndpoint().getUri()).isEqualTo("https://api.github.com/user");
                    assertThat(gitHub.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("id");
                });
    }

    @Test
    void googleIsRegisteredExactlyAsSpringBootRegistersItWithOrWithoutGitHub() {
        springBootOnly.withPropertyValues(GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET).run(springBoot -> {
            final var expected = springBoot.getBean(ClientRegistrationRepository.class).findByRegistrationId("google");

            contextRunner.withPropertyValues(GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, "OAUTH2_GITHUB_CLIENT_ID=")
                    .run(context -> assertSameRegistration(context.getBean(ClientRegistrationRepository.class).findByRegistrationId("google"), expected));
            contextRunner.withPropertyValues(GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET)
                    .run(context -> assertSameRegistration(context.getBean(ClientRegistrationRepository.class).findByRegistrationId("google"), expected));
        });
    }

    @Test
    void aBlankGoogleClientIdStillStopsTheStartAsBefore() {
        contextRunner.withPropertyValues("OAUTH2_GOOGLE_CLIENT_ID=", GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause()
                        .hasMessage("Client id of registration 'google' must not be empty."));
    }

    private static void assertSameRegistration(final ClientRegistration actual, final ClientRegistration expected) {
        assertThat(actual).isNotNull();
        assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
        assertThat(actual.getRedirectUri()).isEqualTo("https://hrva.cc/login/oauth2/code/google");
        assertThat(actual.getScopes()).isEqualTo(Set.of("profile", "email"));
    }

}
