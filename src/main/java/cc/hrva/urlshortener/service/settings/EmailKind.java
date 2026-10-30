package cc.hrva.urlshortener.service.settings;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EmailKind {

    LINK_BLOCKED(SettingKey.EMAIL_LINK_BLOCKED),
    MALWARE_FOUND(SettingKey.EMAIL_MALWARE_FOUND),
    DEAD_LINKS(SettingKey.EMAIL_DEAD_LINKS),
    LINK_EXPIRING(SettingKey.EMAIL_LINK_EXPIRING),
    WEEKLY_SUMMARY(SettingKey.EMAIL_WEEKLY_SUMMARY),
    ADMIN_DAILY(SettingKey.EMAIL_ADMIN_DAILY),
    NEW_USER_NOTICE(SettingKey.EMAIL_NEW_USER_NOTICE),
    ADMIN_WEEKLY_DIGEST(SettingKey.WEEKLY_DIGEST_EMAIL),
    CUSTOM_DOMAIN(SettingKey.EMAIL_CUSTOM_DOMAIN);

    private final SettingKey setting;

}
