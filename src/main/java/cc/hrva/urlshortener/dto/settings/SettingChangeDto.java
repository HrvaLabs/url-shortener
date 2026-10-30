package cc.hrva.urlshortener.dto.settings;

import tools.jackson.databind.JsonNode;

public record SettingChangeDto(JsonNode value) {

}
