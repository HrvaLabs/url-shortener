package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import java.time.LocalDateTime;
import java.util.List;

public record BlocklistSourceResponse(
        Long id,
        String name,
        String url,
        List<String> fallbackUrls,
        SourceKind kind,
        BlocklistAction action,
        boolean enabled,
        int refreshIntervalHours,
        LocalDateTime lastFetchedAt,
        LocalDateTime lastSuccessAt,
        String lastSuccessUrl,
        Long domainCount,
        String listVersion,
        String lastError,
        Long memoryBytes) {

    public static BlocklistSourceResponse from(final BlocklistSource source, final Long memoryBytes) {
        return new BlocklistSourceResponse(
                source.getId(),
                source.getName(),
                source.getUrl(),
                source.getFallbackUrls(),
                source.getKind(),
                source.getAction(),
                source.isEnabled(),
                source.getRefreshIntervalHours(),
                source.getLastFetchedAt(),
                source.getLastSuccessAt(),
                source.getLastSuccessUrl(),
                source.getDomainCount(),
                source.getListVersion(),
                source.getLastError(),
                memoryBytes);
    }

}
