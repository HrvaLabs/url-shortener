package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.settings.SettingResponse;
import cc.hrva.urlshortener.service.settings.EmailKind;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import tools.jackson.databind.JsonNode;

public interface SettingsService {

    boolean isOn(SettingKey key);
    int number(SettingKey key);
    OptionalInt optionalNumber(SettingKey key);
    String text(SettingKey key);
    List<String> addresses(SettingKey key);
    SettingKey settingKey(String name);
    List<SettingResponse> list();
    SettingResponse change(SettingKey key, JsonNode value, String changedBy);
    SettingResponse reset(SettingKey key, String changedBy);
    List<SettingKey> safetySwitchesOff();
    Optional<SettingKey> emailSwitchOff(EmailKind kind);
    void refresh();

}
