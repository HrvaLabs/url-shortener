package cc.hrva.urlshortener.repository;

public record LinkAuditSubject(String shortUrl, String longUrl, String hostname) {
}
