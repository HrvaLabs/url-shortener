package cc.hrva.urlshortener.validator.impl;

import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import lombok.RequiredArgsConstructor;
import cc.hrva.urlshortener.validator.UserValidator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultUserValidator implements UserValidator {

    private final SettingsService settingsService;

    @Override
    public void checkRegistrationEnabled() {
        if (!settingsService.isOn(SettingKey.NEW_SIGNUPS)) {
            throw new ApiException("Creating new accounts is paused.");
        }
    }

}
