package cc.hrva.urlshortener.validator;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.EmailExistsException;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import cc.hrva.urlshortener.validator.impl.DefaultUserValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserValidatorTest {

    private UserValidator userValidator;

    private final DefaultSettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());

    @Mock
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        this.userValidator = new DefaultUserValidator(userRepository, settingsService);
    }

    @Test
    void shouldValidateEmailUniqueness() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

        assertThatCode(() -> userValidator.checkEmailUniqueness("new@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFailWhenEmailExists() {
        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userValidator.checkEmailUniqueness("existing@example.com"))
                .isInstanceOf(EmailExistsException.class)
                .hasMessage("An account with this email already exists");
    }

    @Test
    void newSignupsFollowTheSetting() {
        assertThatCode(() -> userValidator.checkRegistrationEnabled()).doesNotThrowAnyException();

        SettingsServiceFixtures.turnOff(settingsService, SettingKey.NEW_SIGNUPS);

        assertThatThrownBy(() -> userValidator.checkRegistrationEnabled())
                .isInstanceOf(ApiException.class)
                .hasMessage("Registrations are currently disabled");
    }
}
