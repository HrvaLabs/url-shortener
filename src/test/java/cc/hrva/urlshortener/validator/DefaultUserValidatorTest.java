package cc.hrva.urlshortener.validator;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import cc.hrva.urlshortener.validator.impl.DefaultUserValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultUserValidatorTest {

    private UserValidator userValidator;

    private final DefaultSettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());

    @BeforeEach
    void setUp() {
        this.userValidator = new DefaultUserValidator(settingsService);
    }

    @Test
    void newSignupsFollowTheSetting() {
        assertThatCode(() -> userValidator.checkRegistrationEnabled()).doesNotThrowAnyException();

        SettingsServiceFixtures.turnOff(settingsService, SettingKey.NEW_SIGNUPS);

        assertThatThrownBy(() -> userValidator.checkRegistrationEnabled())
                .isInstanceOf(ApiException.class)
                .hasMessage("Creating new accounts is paused.");
    }
}
