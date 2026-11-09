package cc.hrva.urlshortener.service.settings;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SettingGroup {

    ACCESS("Access"),
    LINKS("Links"),
    SAFETY("Safety"),
    ALERTS("Alerts"),
    DIGEST("Digest"),
    EMAILS("Emails"),
    ABUSE_REPORTS("Abuse reports"),
    APP_DIRECTORIES("App directories");

    private final String label;

}
