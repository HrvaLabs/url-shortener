package cc.hrva.urlshortener.validator;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.EmailExistsException;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import cc.hrva.urlshortener.validator.impl.DefaultUserValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserValidatorShould {

    private UserValidator userValidator;

    @Mock
    private UserRepository userRepository;

    private final DefaultSettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());


    private String email;

    @BeforeEach
    void setUp() {
        this.userValidator = new DefaultUserValidator(userRepository, settingsService);

        this.email = "email";
    }

    @Test
    void validateEmailUniqueness() {
        when(userRepository.existsByEmail(email)).thenReturn(false);
        assertThatCode(() -> userValidator.checkEmailUniqueness(email))
                .doesNotThrowAnyException();
    }

    @Test
    void failValidateEmailUniquenessBecauseEmailExists() {
        when(userRepository.existsByEmail(email)).thenReturn(true);
        assertThatThrownBy(() -> userValidator.checkEmailUniqueness(email))
                .isInstanceOf(EmailExistsException.class)
                .hasMessage("An account with this email already exists");
    }

}