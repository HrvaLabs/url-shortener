package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import java.util.List;

public record BlocklistSourceDto(
        String name,
        String url,
        SourceKind kind,
        BlocklistAction action,
        Boolean enabled,
        Integer refreshIntervalHours,
        List<String> fallbackUrls) {}
