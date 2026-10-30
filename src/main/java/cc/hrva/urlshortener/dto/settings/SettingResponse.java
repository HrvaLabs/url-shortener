package cc.hrva.urlshortener.dto.settings;

import cc.hrva.urlshortener.service.settings.SettingGroup;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingType;
import java.time.LocalDateTime;

public record SettingResponse(SettingKey key, SettingGroup group, SettingType type, String label, String description, SettingKey parent,
        boolean safety, Object value, Object defaultValue, boolean changed, String changedBy, LocalDateTime changedAt, Integer min, Integer max,
        boolean nullable, String noneLabel, String unit, boolean available, String unavailableReason) {

}
