package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NewAccounts {

    private final SettingsService settingsService;
    private final ScammerSignalService scammerSignalService;
    private final Clock clock;

    public boolean isNew(final User user) {
        return youngerThanTheSetting(user) || !scammerSignalService.possibleScammers(List.of(user)).isEmpty();
    }

    private boolean youngerThanTheSetting(final User user) {
        final var days = settingsService.number(SettingKey.SIGN_IN_FORM_NEW_ACCOUNT_DAYS);
        return days > 0 && user.getCreateDate() != null
                && user.getCreateDate().plusDays(days).isAfter(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())));
    }

}
