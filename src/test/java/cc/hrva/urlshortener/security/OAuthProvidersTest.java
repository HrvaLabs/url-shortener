package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthProvidersTest {

    @Test
    void aRegistrationWithAClientIdIsConfigured() {
        final var providers = SettingsServiceFixtures.bothProviders();

        assertThat(providers.configured("google")).isTrue();
        assertThat(providers.configured("github")).isTrue();
    }

    @Test
    void aMissingRegistrationIsNotConfigured() {
        final var onlyGoogle = new OAuthProviders(new InMemoryClientRegistrationRepository(
                CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId("test-google-client").clientSecret("test-google-secret").build()));
        final var none = new OAuthProviders(registrationId -> null);

        assertThat(onlyGoogle.configured("google")).isTrue();
        assertThat(onlyGoogle.configured("github")).isFalse();
        assertThat(none.configured("google")).isFalse();
        assertThat(none.configured("github")).isFalse();
        assertThat(onlyGoogle.configured(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void aBlankClientIdIsNotConfigured(final String clientId) {
        final var registration = mock(ClientRegistration.class);
        when(registration.getClientId()).thenReturn(clientId);
        final var providers = new OAuthProviders(registrationId -> registration);

        assertThat(providers.configured("github")).isFalse();
    }

    @Test
    void eachProviderHasItsOwnSwitch() {
        assertThat(OAuthProviders.switchOf(SignInMethod.GOOGLE)).contains(SettingKey.GOOGLE_SIGN_IN);
        assertThat(OAuthProviders.switchOf(SignInMethod.GITHUB)).contains(SettingKey.GITHUB_SIGN_IN);
        assertThat(OAuthProviders.switchOf(SignInMethod.PASSWORD)).isEmpty();
        assertThat(OAuthProviders.switchOf(SignInMethod.PASSKEY)).isEmpty();
    }

}
