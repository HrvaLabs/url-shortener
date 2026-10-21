package cc.hrva.urlshortener.dto;

import java.util.List;

public record LinkDigestEntry(Long reviewUrlId, String shortUrl, String destination, String ownerEmail, List<String> reasons) {
}
