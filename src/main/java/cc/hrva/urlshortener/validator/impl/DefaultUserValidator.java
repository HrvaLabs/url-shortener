package cc.hrva.urlshortener.validator.impl;

import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import lombok.RequiredArgsConstructor;
import cc.hrva.urlshortener.exception.EmailExistsException;
import cc.hrva.urlshortener.validator.UserValidator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultUserValidator implements UserValidator {

    private final UserRepository userRepository;
    private final SettingsService settingsService;

    @Override
    public void checkEmailUniqueness(final String email) {
        if (userRepository.existsByEmail(email)) {
            throw new EmailExistsException("An account with this email already exists");
        }
    }

    @Override
    public void checkRegistrationEnabled() {
        if (!settingsService.isOn(SettingKey.NEW_SIGNUPS)) {
            throw new ApiException("Registrations are currently disabled");
        }
    }

}
