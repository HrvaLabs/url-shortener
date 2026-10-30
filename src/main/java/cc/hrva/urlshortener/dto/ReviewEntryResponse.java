package cc.hrva.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

public record ReviewEntryResponse(
        Long urlId,
        String shortUrl,
        String longUrl,
        String host,
        String registrableDomain,
        Long ownerId,
        String ownerEmail,
        LocalDateTime ownerCreatedAt,
        boolean possibleScammer,
        LocalDateTime createDate,
        List<String> reasons,
        LocalDateTime flaggedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String domain) {
}
