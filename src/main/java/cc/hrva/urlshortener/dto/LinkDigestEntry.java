package cc.hrva.urlshortener.dto;

import java.util.List;

public record LinkDigestEntry(String shortUrl, String longUrl, String ownerEmail, List<String> notes) {
}
